package com.calles.platform.interaction.infrastructure.scheduling;

import com.calles.platform.interaction.application.outbox.InteractionOutboxCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 互动 Outbox 已发布记录定时清理任务。
 * 任务异常仅影响本轮清理，不传播到业务事务或派发线程。
 */
@Component
public class InteractionOutboxCleanupJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionOutboxCleanupJob.class);

    private final InteractionOutboxCleanupService cleanupService;

    public InteractionOutboxCleanupJob(InteractionOutboxCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /**
     * 按独立清理间隔触发一轮批量删除。
     */
    @Scheduled(fixedDelayString = "#{T(org.springframework.boot.convert.DurationStyle).detectAndParse('${interaction.outbox.cleanup-interval:1h}').toMillis()}")
    public void cleanupPublished() {
        try {
            cleanupService.cleanup();
        } catch (Exception e) {
            LOGGER.error("Interaction Outbox 清理执行失败: {}", e.getMessage(), e);
        }
    }
}
