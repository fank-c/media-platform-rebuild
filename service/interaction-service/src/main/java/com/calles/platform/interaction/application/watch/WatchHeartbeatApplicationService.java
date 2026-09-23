package com.calles.platform.interaction.application.watch;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.domain.repository.WatchHistoryRepository;
import com.calles.platform.interaction.infrastructure.redis.HeartbeatDedupService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频播放心跳上报与观看历史应用服务。
 *
 * <p>由播放端定时心跳（Heartbeat）驱动，维护断点续播位置、累计有效时长，并配合持久化防重窗口原子累加播放量与生成领域事件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchHeartbeatApplicationService {

    /** 判定为单次有效播放的观看时长门槛 (秒)。 */
    private static final int VALID_WATCH_DURATION_THRESHOLD_SECONDS = 5;

    /** 判定单次有效播放的去重时间窗口：30 分钟。 */
    private static final Duration PLAY_DEDUP_WINDOW = Duration.ofMinutes(30);

    private final WatchHistoryRepository historyRepository;
    private final VideoCounterRepository counterRepository;
    private final HeartbeatDedupService dedupService;
    private final InteractionEventPublisher eventPublisher;

    /**
     * 处理播放端心跳上报。
     *
     * @param vid 视频业务公开短码
     * @param userId 已通过入口鉴权的登录用户 ID
     * @param position 当前播放头秒数位置
     * @param deltaDuration 距上次心跳新增播放秒数
     * @param videoDuration 视频总秒数
     * @return 更新后的观看历史实体
     */
    @Transactional(rollbackFor = Exception.class)
    public WatchHistory processHeartbeat(String vid, String userId, int position,
                                         int deltaDuration, int videoDuration) {
        // 步骤 1: 物理检索或初始化该用户在该视频的观看历史（含已逻辑删除记录，防范删记录刷播放量）
        Optional<WatchHistory> opt = historyRepository.findPhysicalByUserAndVid(userId, vid);
        WatchHistory history;
        boolean wasRevived = false;

        if (opt.isEmpty()) {
            history = WatchHistory.create(userId, vid, position, deltaDuration, videoDuration);
            historyRepository.save(history);
        } else {
            history = opt.get();
            if (history.isDeleted()) {
                // 步骤 1.1: 针对已伪删除的记录进行自愈复活，重置播放头但严格保留原有 last_valid_play_at
                history.revive(position, deltaDuration, videoDuration);
                wasRevived = true;
            } else {
                history.recordHeartbeat(position, deltaDuration, videoDuration);
            }
        }

        // 步骤 2: 有效播放量持久化防重判定与原子累加
        // 规则：累计观看达到 5 秒门槛，且数据库持久化时间戳满足 30 分钟去重窗口（复活记录继承原时间戳，删记录无法重置防刷窗口）
        LocalDateTime now = LocalDateTime.now();
        if (history.getWatchedDuration() >= VALID_WATCH_DURATION_THRESHOLD_SECONDS
                && history.shouldCountValidPlay(now, PLAY_DEDUP_WINDOW)) {
            // 步骤 2.1: 标记并持久化本次有效播放时间戳
            history.markValidPlay(now);
            if (wasRevived) {
                historyRepository.revive(history);
            } else {
                historyRepository.update(history);
            }

            // 步骤 2.2: 自增播放计数并写入 Outbox 领域事件
            counterRepository.incrementViewCount(vid, 1L);
            eventPublisher.publishVideoAction(VideoActionPayload.play(userId, vid));

            // 步骤 2.3: 刷新 Redis 窗口缓存辅助轻量降噪
            dedupService.tryAcquireFirstPlay(userId, vid);
            log.info("用户 [{}] 针对视频 [{}] 达成有效播放持久化条件，自增播放量计数并写入 Outbox", userId, vid);
        } else {
            if (wasRevived) {
                historyRepository.revive(history);
            } else {
                historyRepository.update(history);
            }
        }

        return history;
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
