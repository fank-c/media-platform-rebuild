package com.calles.platform.interaction.infrastructure.outbox.dispatch;

import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Interaction Outbox 的统一任务分发调度器。
 */
@Component
public class InteractionOutboxDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionOutboxDispatcher.class);

    private final InteractionOutboxRepository repository;
    private final InteractionOutboxPublisher publisher;
    private final InteractionOutboxProperties properties;
    private final String owner = UUID.randomUUID().toString();

    public InteractionOutboxDispatcher(InteractionOutboxRepository repository,
                                       InteractionOutboxPublisher publisher,
                                       InteractionOutboxProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
    }

    /**
     * 按指定事件 ID 执行单条认领与分发投递。
     *
     * @param eventId 待分发的事件 ID
     */
    public void dispatchByEventId(String eventId) {
        if (!properties.isEnabled() || !properties.isDispatchEnabled()) {
            LOGGER.debug("Interaction Outbox 总开关或派发开关已关闭，跳过分发: eventId={}", eventId);
            return;
        }

        ClaimedOutboxMessage claimed = repository.claimByEventId(
                eventId,
                owner,
                properties.getLease(),
                properties.getMaxAttempts()
        );

        if (claimed != null) {
            publisher.publish(claimed);
            return;
        }

        if (repository.markExhaustedIfEligible(eventId, properties.getMaxAttempts())) {
            LOGGER.warn("Interaction Outbox 事件重试次数已耗尽并标记为 FAILED: eventId={}", eventId);
        }
    }

    /**
     * 执行一轮定时扫描候选批次。
     */
    public void dispatchScanBatch() {
        if (!properties.isEnabled() || !properties.isDispatchEnabled()) {
            return;
        }

        try {
            List<String> eventIds = repository.findClaimableEventIds(
                    properties.getBatchSize(),
                    properties.getMaxAttempts()
            );

            for (String eventId : eventIds) {
                try {
                    dispatchByEventId(eventId);
                } catch (Exception exception) {
                    LOGGER.error("Interaction Outbox 扫描单条事件派发异常: eventId={}", eventId, exception);
                }
            }

            List<String> exhaustedIds = repository.findExhaustedEventIds(
                    properties.getBatchSize(),
                    properties.getMaxAttempts()
            );
            for (String exhaustedId : exhaustedIds) {
                repository.markExhaustedIfEligible(exhaustedId, properties.getMaxAttempts());
            }
        } catch (Exception exception) {
            LOGGER.error("Interaction Outbox 批次扫描执行异常: {}", exception.getMessage(), exception);
        }
    }
}
