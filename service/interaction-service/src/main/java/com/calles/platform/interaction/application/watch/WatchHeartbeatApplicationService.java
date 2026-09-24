package com.calles.platform.interaction.application.watch;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.domain.repository.WatchHistoryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频播放心跳与观看历史应用服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>观看事实维护：持续更新播放头断点位置（last_position）、累计有效时长（watched_duration）与心跳时间（last_watch_at）；</li>
 *   <li>可重复有效播放判定：累计有效观看时长达标（默认 5 秒）后，通过数据库行级 CAS 原子抢占本冷却周期（默认 6 小时）的播放资格，杜绝并发与重复刷量；</li>
 *   <li>完播原子置位：进度达到 90% 时通过数据库 CAS 原子置位，严格单次触发完播事件；</li>
 *   <li>历史查询与清理：提供断点查询、历史分页与删除。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class WatchHeartbeatApplicationService {

    private final WatchHistoryRepository historyRepository;
    private final VideoCounterRepository counterRepository;
    private final InteractionEventPublisher eventPublisher;
    private final Duration repeatWindow;
    private final Duration validPlayThreshold;

    public WatchHeartbeatApplicationService(
            WatchHistoryRepository historyRepository,
            VideoCounterRepository counterRepository,
            InteractionEventPublisher eventPublisher,
            @Value("${interaction.watch.repeat-window:6h}") Duration repeatWindow,
            @Value("${interaction.watch.valid-play-threshold:5s}") Duration validPlayThreshold) {
        this.historyRepository = historyRepository;
        this.counterRepository = counterRepository;
        this.eventPublisher = eventPublisher;
        this.repeatWindow = repeatWindow != null ? repeatWindow : Duration.ofHours(6);
        this.validPlayThreshold = validPlayThreshold != null ? validPlayThreshold : Duration.ofSeconds(5);
    }

    /**
     * 处理播放端周期心跳上报（全生命周期统一入口）。
     *
     * <p>处理流程：
     * 1. 查询 user_id + vid 的物理观看历史（含已伪删除记录）；
     * 2. 若不存在：新建记录，达标直接记录时间戳并处理首次有效播放；若并发冲突则捕获 DuplicateKeyException 转入已有记录流程；
     * 3. 若存在：拆分为两步——步骤一更新观看事实；步骤二达标后执行数据库 CAS 抢占播放资格（成功则计数+1写Outbox，失败则不计数不写事件）；
     * 4. 进度达 90% 完播时通过 CAS 原子置位触发单次完播事件。
     * </p>
     *
     * @param vid 视频业务公开短码
     * @param userId 已鉴权登录用户 ID
     * @param position 当前播放头秒数位置
     * @param deltaDuration 距上次心跳增量秒数
     * @param videoDuration 视频总秒数
     * @return 更新后的观看历史实体
     */
    @Transactional(rollbackFor = Exception.class)
    public WatchHistory processHeartbeat(String vid, String userId, int position,
                                         int deltaDuration, int videoDuration) {
        LocalDateTime now = LocalDateTime.now();
        Optional<WatchHistory> opt = historyRepository.findPhysicalByUserAndVid(userId, vid);
        WatchHistory history;

        if (opt.isEmpty()) {
            // 步骤 1: 首次观看该视频，初始化观看历史实体
            history = WatchHistory.create(userId, vid, position, deltaDuration, videoDuration);
            boolean initialMetThreshold = history.getWatchedDuration() >= validPlayThreshold.toSeconds();
            if (initialMetThreshold) {
                // 首次上报即达标时，直接记录首次有效播放防重时间戳
                history.markValidPlay(now);
            }

            try {
                historyRepository.save(history);
                if (initialMetThreshold) {
                    // 步骤 1.1: 成功插入且首次达标，累加播放量并生成标准 PLAY Outbox 事件
                    counterRepository.incrementViewCount(vid, 1L);
                    eventPublisher.publishVideoAction(VideoActionPayload.play(userId, vid));
                    log.info("用户 [{}] 首次观看视频 [{}]（新建历史且达标），播放量 +1 并发布 PLAY 事件", userId, vid);
                }
            } catch (DuplicateKeyException e) {
                // 步骤 1.2: 多端同毫秒首次心跳并发插入时的冲突兜底：捕获唯一键异常，透明转入已有历史更新分支
                log.debug("捕获到多端同毫秒首次心跳并发插入冲突，自动自愈转入更新分支: userId={}, vid={}", userId, vid);
                history = historyRepository.findPhysicalByUserAndVid(userId, vid)
                        .orElseThrow(() -> e);
                updateExistingHistory(history, position, deltaDuration, videoDuration, now);
            }
        } else {
            history = opt.get();
            updateExistingHistory(history, position, deltaDuration, videoDuration, now);
        }

        // 步骤 3: 完播判定：达到 90% 阈值且尚未标记完播时，采用数据库 CAS 原子防重置位
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
     * 起播辅助方法（与心跳共用统一逻辑）。
     *
     * @param vid 视频编码
     * @param userId 用户 ID
     * @return 观看历史实体
     */
    @Transactional(rollbackFor = Exception.class)
    public WatchHistory startPlay(String vid, String userId) {
        return processHeartbeat(vid, userId, 0, 0, 0);
    }

    /**
     * 已有观看历史的处理逻辑：严格解耦“观看事实更新”与“播放资格 CAS 抢占”。
     */
    private void updateExistingHistory(WatchHistory history, int position, int deltaDuration,
                                       int videoDuration, LocalDateTime now) {
        // 步骤 1: 更新观看事实（断点位置、累计时长与活跃时间），严格不修改播放资格时间戳
        if (history.isDeleted()) {
            history.revive(position, deltaDuration, videoDuration);
            historyRepository.revive(history);
        } else {
            history.recordHeartbeat(position, deltaDuration, videoDuration);
            historyRepository.update(history);
        }

        // 步骤 2: 尝试抢占有效播放资格
        // 为什么先判断有效观看阈值：必须确保用户实际观看时长达到业务门槛（默认 5 秒），避免用户刚点进即关闭造成虚假播放计费
        long thresholdSeconds = validPlayThreshold.toSeconds();
        if (history.getWatchedDuration() >= thresholdSeconds) {
            // 为什么必须通过数据库条件更新：依赖数据库行级锁排他判断冷却边界，杜绝并发心跳造成重复双发
            if (tryClaimValidPlay(history, now)) {
                // CAS 成功：成功抢到本周期有效播放资格，推进实体状态、累加播放量并生成标准 PLAY 事件
                history.markValidPlay(now);
                counterRepository.incrementViewCount(history.getVid(), 1L);
                eventPublisher.publishVideoAction(VideoActionPayload.play(history.getUserId(), history.getVid()));
                log.info("用户 [{}] 针对视频 [{}] 达成有效播放 (CAS抢占成功)，播放量 +1 并发布 PLAY 事件",
                        history.getUserId(), history.getVid());
            } else {
                // CAS 失败：仍在防刷冷却期或已被并发心跳抢占，静默跳过计数与事件，杜绝虚假刷量
                log.debug("用户 [{}] 针对视频 [{}] 心跳已达有效阈值，但处于冷却期或被并发抢占，跳过重复计数",
                        history.getUserId(), history.getVid());
            }
        }
    }

    /**
     * 尝试原子抢占当前冷却周期的有效播放资格。
     *
     * <p>为什么必须通过数据库条件更新：
     * 高并发心跳、多端同看或网络重试时，多个请求可能同时查询到过期或未标记的防重时间戳。
     * 只有依赖数据库行级锁排他执行 {@code claimValidPlay}，才能确保同一个冷却周期内仅有单个请求抢占成功。
     * 为什么 CAS 失败时不能增加计数或写事件：
     * CAS 返回 0 说明当前仍处于防刷冷却期，或已被并发到达的其他线程先行抢占；此时强行计数将导致虚假刷量与下游事件风暴。
     * </p>
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
