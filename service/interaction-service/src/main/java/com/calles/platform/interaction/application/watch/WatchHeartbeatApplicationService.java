package com.calles.platform.interaction.application.watch;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.domain.repository.WatchHistoryRepository;
import com.calles.platform.interaction.exception.LockAcquireTimeoutException;
import com.calles.platform.interaction.infrastructure.redis.RedisLockService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 视频播放心跳与观看历史应用服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>分布式锁与事务分层：锁在外层、事务在内层，确保事务完全提交后才释放锁，彻底消灭快照读与并发竞态；</li>
 *   <li>锁超时平滑降级：并发心跳锁等待超时时优雅只读降级返回当前断点，杜绝向前端抛出 500 错误；</li>
 *   <li>输入规整与边界防御：拦截负数与越界断点，安全截断异常超大增量时长（上限 15 秒），防止快进恶意刷量；</li>
 *   <li>创建与发布严格解耦：0 秒初始心跳纯净创建事实记录，绝不增加播放量、绝不发任何事件；</li>
 *   <li>事务与计数单向强一致：通过 {@code afterCommit} 机制，保证仅在 Outbox 事件与 CAS 真正落库提交后才递增 Redis 播放量，事务回滚绝不误增。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class WatchHeartbeatApplicationService {

    /** 视频心跳分布式锁 Key 前缀。 */
    private static final String WATCH_LOCK_PREFIX = "int:lock:watch:";

    /** 分布式锁最大排队等待时间 (3 秒)。 */
    private static final Duration LOCK_WAIT_TIME = Duration.ofSeconds(3);

    /** 单次心跳允许的最大有效增量时长 (秒)，超过此值予以截断以防刷量。 */
    private static final int MAX_HEARTBEAT_DELTA_SECONDS = 15;

    private final WatchHistoryRepository historyRepository;
    private final VideoCounterRepository counterRepository;
    private final InteractionEventPublisher eventPublisher;
    private final RedisLockService lockService;
    private final TransactionTemplate transactionTemplate;
    private final Duration repeatWindow;
    private final Duration validPlayThreshold;

    public WatchHeartbeatApplicationService(
            WatchHistoryRepository historyRepository,
            VideoCounterRepository counterRepository,
            InteractionEventPublisher eventPublisher,
            RedisLockService lockService,
            TransactionTemplate transactionTemplate,
            @Value("${interaction.watch.repeat-window:6h}") Duration repeatWindow,
            @Value("${interaction.watch.valid-play-threshold:5s}") Duration validPlayThreshold) {
        this.historyRepository = historyRepository;
        this.counterRepository = counterRepository;
        this.eventPublisher = eventPublisher;
        this.lockService = lockService;
        this.transactionTemplate = transactionTemplate;
        this.repeatWindow = repeatWindow != null ? repeatWindow : Duration.ofHours(6);
        this.validPlayThreshold = validPlayThreshold != null ? validPlayThreshold : Duration.ofSeconds(5);
    }

    /**
     * 处理播放端周期心跳上报（全生命周期统一入口）。
     *
     * @param vid 视频业务公开短码
     * @param userId 已鉴权登录用户 ID
     * @param position 当前播放头秒数位置
     * @param deltaDuration 距上次心跳增量秒数
     * @param videoDuration 视频总秒数
     * @return 更新后的观看历史实体
     */
    public WatchHistory processHeartbeat(String vid, String userId, int position,
                                         int deltaDuration, int videoDuration) {
        // 步骤 0: 参数防御性校验与安全规整
        String cleanVid = vid != null ? vid.trim() : "";
        String cleanUserId = userId != null ? userId.trim() : "";
        if (cleanVid.isBlank() || cleanUserId.isBlank()) {
            throw new IllegalArgumentException("视频编码与用户ID不能为空");
        }

        int safeVideoDuration = Math.max(0, videoDuration);
        int safePosition = Math.max(0, position);
        if (safeVideoDuration > 0) {
            safePosition = Math.min(safePosition, safeVideoDuration);
        }

        int safeDelta = Math.max(0, deltaDuration);
        if (safeDelta > MAX_HEARTBEAT_DELTA_SECONDS) {
            log.debug("心跳增量异常偏大 ({}s)，安全截断为 {}s: userId={}, vid={}", safeDelta, MAX_HEARTBEAT_DELTA_SECONDS, cleanUserId, cleanVid);
            safeDelta = MAX_HEARTBEAT_DELTA_SECONDS;
        }

        String lockKey = WATCH_LOCK_PREFIX + cleanUserId + ":" + cleanVid;

        // 步骤 1: 锁在外层保护（由 Redisson 看门狗自动续期），避免事务未提交前释放锁
        try {
            int finalSafePosition = safePosition;
            int finalSafeDelta = safeDelta;
            return lockService.executeWithLock(lockKey, LOCK_WAIT_TIME, () -> {
                // 步骤 2: 事务在内层执行，保证事务在锁释放前完全 COMMIT
                return transactionTemplate.execute(status ->
                        doProcessHeartbeatInTransaction(cleanVid, cleanUserId, finalSafePosition, finalSafeDelta, safeVideoDuration));
            });
        } catch (LockAcquireTimeoutException e) {
            // 步骤 1.1: 仅对分布式锁排队超时进行只读平滑降级，返回已有断点；业务任务内部异常绝不在此拦截
            log.warn("心跳获取分布式锁排队超时，触发平滑降级: userId={}, vid={}, error={}", cleanUserId, cleanVid, e.getMessage());
            int finalSafePosition = safePosition;
            int finalSafeDelta = safeDelta;
            return historyRepository.findByUserAndVid(cleanUserId, cleanVid)
                    .orElseGet(() -> WatchHistory.create(cleanUserId, cleanVid, finalSafePosition, finalSafeDelta, safeVideoDuration));
        }
    }

    /**
     * 事务内核心业务逻辑：负责观看事实持久化、唯一CAS有效播放判定与完播置位。
     */
    private WatchHistory doProcessHeartbeatInTransaction(String vid, String userId, int position,
                                                         int deltaDuration, int videoDuration) {
        LocalDateTime now = LocalDateTime.now();
        Optional<WatchHistory> opt = historyRepository.findPhysicalByUserAndVid(userId, vid);
        WatchHistory history;

        if (opt.isEmpty()) {
            // 步骤 2.1: 首次观看（0秒初始心跳），纯净新建记录，仅落盘断点事实，绝不增加播放量、绝不发事件
            history = WatchHistory.create(userId, vid, position, deltaDuration, videoDuration);
            historyRepository.save(history);
            log.debug("用户 [{}] 首次观看视频 [{}]（0秒心跳），初始化观看事实成功，暂不触发播放计费与事件", userId, vid);
        } else {
            // 步骤 2.2: 存在历史，更新断点事实（last_position、watched_duration、last_watch_at）或自愈复活
            history = opt.get();
            if (history.isDeleted()) {
                history.revive(position, deltaDuration, videoDuration);
                historyRepository.revive(history);
            } else {
                history.recordHeartbeat(position, deltaDuration, videoDuration);
                historyRepository.update(history);
            }
        }

        // 步骤 3: 全局唯一的有效播放门槛判定与 CAS 抢占
        // 只有当累计观看时长达到业务门槛（默认 5 秒）时，才尝试通过数据库 CAS 抢占播放资格
        long thresholdSeconds = validPlayThreshold.toSeconds();
        if (history.getWatchedDuration() >= thresholdSeconds) {
            if (tryClaimValidPlay(history, now)) {
                // 步骤 3.1: CAS 抢占成功，写入一条标准 PLAY:ACTIVE 领域事件入 Outbox 表
                history.markValidPlay(now);
                eventPublisher.publishVideoAction(VideoActionPayload.play(userId, vid));

                // 步骤 3.2: 注册事务提交后回调（afterCommit），确保 Outbox 事件与事务完全 COMMIT 后才增加 Redis 播放计数
                // 若 Outbox 插入异常导致事务回滚，afterCommit 绝不执行，杜绝播放量虚高
                registerAfterCommitIncrement(vid);
                log.info("用户 [{}] 针对视频 [{}] 累计时长达标且 CAS 抢占成功，写 Outbox 并在事务提交后递增播放量", userId, vid);
            } else {
                // 步骤 3.3: 仍在防刷冷却期或已被抢占，静默跳过计数与事件，杜绝虚假刷量
                log.debug("用户 [{}] 针对视频 [{}] 心跳已达有效阈值，但处于冷却期或已被抢占，跳过重复计数", userId, vid);
            }
        }

        // 步骤 4: 完播判定：达到 90% 阈值且尚未标记完播时，采用完播数据库 CAS 原子置位
        if (videoDuration > 0 && position >= (int) (videoDuration * WatchHistory.COMPLETION_THRESHOLD_RATIO)) {
            if (!history.isCompleted()) {
                int affected = historyRepository.markCompletedIfUncompleted(history.getId());
                if (affected > 0) {
                    history.markCompleted();
                    eventPublisher.publishVideoAction(VideoActionPayload.playComplete(userId, vid));
                    log.info("用户 [{}] 针对视频 [{}] 达成完播 (CAS原子置位成功)，发布完播事件", userId, vid);
                }
            }
        }

        return history;
    }

    /**
     * 注册数据库事务成功提交后的播放计数递增回调。
     */
    private void registerAfterCommitIncrement(String vid) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    counterRepository.incrementViewCount(vid, 1L);
                }
            });
        } else {
            counterRepository.incrementViewCount(vid, 1L);
        }
    }

    /**
     * 起播辅助方法（与心跳共用统一逻辑）。
     *
     * @param vid 视频编码
     * @param userId 用户 ID
     * @return 观看历史实体
     */
    public WatchHistory startPlay(String vid, String userId) {
        return processHeartbeat(vid, userId, 0, 0, 0);
    }

    /**
     * 尝试原子抢占当前冷却周期的有效播放资格。
     *
     * @param history 观看历史实体
     * @param now 当前时间戳
     * @return true 若成功抢到本周期有效播放资格，false 若在冷却期内或已被其他线程抢占
     */
    private boolean tryClaimValidPlay(WatchHistory history, LocalDateTime now) {
        LocalDateTime cooldownBoundary = now.minus(this.repeatWindow);
        int affected = historyRepository.claimValidPlay(history.getId(), now, cooldownBoundary);
        return affected > 0;
    }

    /**
     * 获取用户在指定视频上的断点播放进度。
     *
     * @param vid 视频编码
     * @param userId 用户 ID
     * @return 观看历史 (包含 lastPosition)
     */
    public Optional<WatchHistory> getWatchProgress(String vid, String userId) {
        return historyRepository.findByUserAndVid(userId, vid);
    }

    /**
     * 分页查询用户历史观看记录列表。
     *
     * @param userId 用户 ID
     * @param page 当前页码 (从 1 起始)
     * @param size 每页大小
     * @return 历史记录列表
     */
    public List<WatchHistory> getHistoryPage(String userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        int offset = (safePage - 1) * safeSize;
        return historyRepository.findByUserId(userId, offset, safeSize);
    }

    /**
     * 删除单条视频观看历史。
     *
     * @param vid 视频编码
     * @param userId 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeHistory(String vid, String userId) {
        historyRepository.deleteByUserAndVid(userId, vid);
    }

    /**
     * 清空当前用户全部观看历史。
     *
     * @param userId 用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void clearAllHistory(String userId) {
        historyRepository.deleteAllByUserId(userId);
    }
}
