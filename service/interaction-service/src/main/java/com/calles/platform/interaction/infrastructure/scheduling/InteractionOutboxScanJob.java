package com.calles.platform.interaction.infrastructure.scheduling;

import com.calles.platform.interaction.infrastructure.outbox.dispatch.InteractionOutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 互动服务发件箱定时自愈与补偿扫描任务。
 *
 * <p>定期扫描 interaction_outbox 表中未成功投递、租约超期或重试到达允许时间的记录，
 * 通过 CAS 抢占并重试投递，确保领域事件 At-least-once 绝对不丢失。</p>
 */
@Component
public class InteractionOutboxScanJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionOutboxScanJob.class);

    private final InteractionOutboxDispatcher dispatcher;

    public InteractionOutboxScanJob(InteractionOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 固定间隔触发一轮扫描。
     */
    @Scheduled(fixedDelayString = "${interaction.outbox.poll-interval:5s}")
    public void scanAndDispatch() {
        try {
            dispatcher.dispatchScanBatch();
        } catch (Exception e) {
            LOGGER.error("Interaction Outbox 定时扫描执行失败: {}", e.getMessage(), e);
        }
    }
}
