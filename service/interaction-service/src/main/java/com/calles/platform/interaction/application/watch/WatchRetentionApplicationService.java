package com.calles.platform.interaction.application.watch;

import com.calles.platform.interaction.config.InteractionWatchProperties;
import com.calles.platform.interaction.domain.repository.WatchEventClaimRepository;
import com.calles.platform.interaction.domain.repository.WatchProgressRepository;
import com.calles.platform.interaction.domain.repository.WatchSessionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 观看数据保留期清理应用服务。
 *
 * <p>职责边界：只做有界、可重复执行的保留期清理，不参与任何心跳判定。</p>
 *
 * <p>为什么必须存在：观看会话与事件凭据按"每次观看"增长，没有清理会无限膨胀。
 * 清理安全性依据：冷却与防重的时间戳保存在 {@code interaction_watch_progress}（且仅在保留期外、
 * 已隐藏时才删除），而每会话凭据由服务端随机会话 ID 唯一标识，
 * 因此删除保留期外的会话与凭据不会重新开启任何播放量计数。</p>
 *
 * <p>执行顺序（同一事务内）：解除长期无心跳的活跃会话引用 → 删除超期会话 → 删除超期凭据 → 删除超期隐藏进度。
 * 前两步的顺序不可颠倒，否则仍被进度引用的会话无法被删除。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchRetentionApplicationService {

    private final WatchProgressRepository progressRepository;
    private final WatchSessionRepository sessionRepository;
    private final WatchEventClaimRepository claimRepository;
    private final InteractionWatchProperties properties;

    /**
     * 按保留期清理观看会话、事件凭据与已隐藏的历史展示记录。
     *
     * <p>每次调用最多处理 {@code cleanup-batch-size} 行，避免长事务锁表。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpired() {
        // 保留期缺失时回退默认 30 天，且严格校验 retention 不小于 sessionTimeout 与 repeatWindow，避免误删导致会话/冷却失效
        Duration retention = getDuration();
        LocalDateTime threshold = LocalDateTime.now().minus(retention);
        int batchSize = Math.max(1, properties.getCleanupBatchSize());

        int detached = progressRepository.detachStaleActiveSessions(threshold, batchSize);
        int deletedSessions = sessionRepository.deleteStaleBefore(threshold, batchSize);
        int deletedClaims = claimRepository.deleteBefore(threshold, batchSize);
        int deletedProgress = progressRepository.deleteHiddenBefore(threshold, batchSize);

        if (detached + deletedSessions + deletedClaims + deletedProgress > 0) {
            log.info("观看数据保留期清理完成: threshold={}, detachedSessions={}, deletedSessions={}, deletedClaims={}, deletedProgress={}",
                    threshold, detached, deletedSessions, deletedClaims, deletedProgress);
        } else {
            log.debug("观看数据保留期清理完成，无超期数据: threshold={}", threshold);
        }
    }

    private Duration getDuration() {
        Duration retention = properties.getRetention() != null ? properties.getRetention() : Duration.ofDays(30);
        Duration sessionTimeout = properties.getSessionTimeout() != null ? properties.getSessionTimeout() : Duration.ofMinutes(30);
        Duration repeatWindow = properties.getRepeatWindow() != null ? properties.getRepeatWindow() : Duration.ofHours(6);
        if (retention.compareTo(sessionTimeout) < 0 || retention.compareTo(repeatWindow) < 0) {
            throw new IllegalStateException("数据保留期不能小于会话超时时间或播放量重复冷却窗口: retention="
                    + retention + ", sessionTimeout=" + sessionTimeout + ", repeatWindow=" + repeatWindow);
        }
        return retention;
    }
}
