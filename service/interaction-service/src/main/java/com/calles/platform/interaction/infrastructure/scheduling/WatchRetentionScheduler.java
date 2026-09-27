package com.calles.platform.interaction.infrastructure.scheduling;

import com.calles.platform.interaction.application.watch.WatchRetentionApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 观看数据保留期清理后台调度任务触发器。
 *
 * <p>遵循关注点分离原则，仅触发调度心跳，清理编排与边界控制由
 * {@link WatchRetentionApplicationService} 承担。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchRetentionScheduler {

    /** 观看数据保留期清理应用用例。 */
    private final WatchRetentionApplicationService retentionService;

    /**
     * 周期性清理超过保留期的观看会话、事件凭据与已隐藏进度。
     */
    @Scheduled(fixedDelayString = "${interaction.watch.cleanup-rate-ms:3600000}")
    public void cleanup() {
        retentionService.cleanupExpired();
    }
}
