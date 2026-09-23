package com.calles.platform.user.infrastructure.outbox.dispatch;

import com.calles.platform.user.config.UserOutboxProperties;
import com.calles.platform.user.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.user.infrastructure.outbox.persistence.UserOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * User Outbox 的单条 RabbitMQ 消息发布执行器。
 */
@Component
public class UserOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserOutboxPublisher.class);

    public static final String MEDIA_EVENTS_EXCHANGE = "media.platform.events";
    public static final String AUTHOR_ACTION_ROUTING_KEY = "interaction.author-action.v1";

    private final UserOutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final UserOutboxProperties properties;

    public UserOutboxPublisher(UserOutboxRepository repository,
                               RabbitTemplate rabbitTemplate,
                               UserOutboxProperties properties) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * 发送一条已被当前实例认领成功的领域事件消息。
     *
     * @param claimed 当前实例持有的不可变发送快照
     */
    public void publish(ClaimedOutboxMessage claimed) {
        CorrelationData correlation = new CorrelationData(claimed.eventId());
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setMessageId(claimed.eventId());
        if (claimed.traceId() != null && !claimed.traceId().isBlank()) {
            messageProperties.setHeader("traceId", claimed.traceId());
        }

        Message message = new Message(claimed.payload().getBytes(StandardCharsets.UTF_8), messageProperties);

        try {
            // 以统一的 interaction.author-action.v1 路由键发送至 media.platform.events
            rabbitTemplate.send(MEDIA_EVENTS_EXCHANGE, AUTHOR_ACTION_ROUTING_KEY, message, correlation);

            // 有界阻塞等待 Broker Confirm 回执
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);

            if (confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
                String reason = (confirm != null && confirm.getReason() != null)
                        ? confirm.getReason()
                        : "消息未被 Broker 确认或无法路由至任何消费队列";
                throw new IllegalStateException(reason);
            }

            // 发布成功，回写 PUBLISHED
            if (!repository.markPublished(claimed)) {
                LOGGER.warn("Outbox 发布确认后租约令牌已失效或被覆盖: eventId={}", claimed.eventId());
                return;
            }

            LOGGER.info("User Outbox 领域事件成功投递: eventId={}, eventType={}, attempt={}",
                    claimed.eventId(), claimed.eventType(), claimed.attempts());
        } catch (Exception exception) {
            handlePublishFailure(claimed, exception);
        }
    }

    private void handlePublishFailure(ClaimedOutboxMessage claimed, Exception exception) {
        String errorCode = exception.getClass().getSimpleName();
        LOGGER.warn("User Outbox 投递失败，触发重试退避: eventId={}, eventType={}, attempt={}, error={}",
                claimed.eventId(), claimed.eventType(), claimed.attempts(), exception.getMessage());
        repository.markFailed(claimed, properties.getMaxAttempts(), errorCode);
    }
}
