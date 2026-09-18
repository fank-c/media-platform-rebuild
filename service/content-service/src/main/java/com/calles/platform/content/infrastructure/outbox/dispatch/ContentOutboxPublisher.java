package com.calles.platform.content.infrastructure.outbox.dispatch;

import com.calles.platform.content.config.ContentMessagingConfiguration;
import com.calles.platform.content.config.ContentOutboxProperties;
import com.calles.platform.content.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.content.infrastructure.outbox.persistence.ContentOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Content Outbox 的统一单条 RabbitMQ 消息发布执行器。
 *
 * <p>职责与机制说明：
 * <ul>
 *   <li><b>所属边界</b>：发件箱消息发送终端，专职网络投递与 Confirm 确认等待；</li>
 *   <li><b>无损路由</b>：以已认领快照中的 {@code eventType} 作为精确 Topic Routing Key 投递至 {@code media.platform.events}；</li>
 *   <li><b>幂等与溯源</b>：在 AMQP 属性中植入稳定的 {@code messageId} 与 {@code traceId} 请求头；</li>
 *   <li><b>强可靠状态机</b>：仅在收到 Broker ACK 且未发生 Return 回退时，才条件流转为 PUBLISHED；网络超时或 NACK 时自动触发退避回写。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class ContentOutboxPublisher {

    /** 发件箱持久化仓储。 */
    private final ContentOutboxRepository repository;

    /** RabbitMQ 发送客户端。 */
    private final RabbitTemplate rabbitTemplate;

    /** 发件箱运行参数。 */
    private final ContentOutboxProperties properties;

    public ContentOutboxPublisher(ContentOutboxRepository repository,
                                  RabbitTemplate rabbitTemplate,
                                  ContentOutboxProperties properties) {
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
        // 步骤 1：构造关联数据 CorrelationData 与 AMQP 消息属性（携带 messageId 与 traceId）
        CorrelationData correlation = new CorrelationData(claimed.eventId());
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setMessageId(claimed.eventId());
        if (claimed.traceId() != null && !claimed.traceId().isBlank()) {
            messageProperties.setHeader("traceId", claimed.traceId());
        }

        Message message = new Message(claimed.payload().getBytes(StandardCharsets.UTF_8), messageProperties);

        try {
            // 步骤 2：以领域事件类型为路由键发送至统一交换机
            rabbitTemplate.send(ContentMessagingConfiguration.MEDIA_EVENTS_EXCHANGE,
                    claimed.eventType(), message, correlation);

            // 步骤 3：有界阻塞等待 Broker Confirm ACK 回执
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);

            // 步骤 4：校验 Confirm 状态及是否发生不可路由回退 (Return)
            if (confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
                String reason = (confirm != null && confirm.getReason() != null)
                        ? confirm.getReason()
                        : "消息未被 Broker 确认或无法路由至任何消费队列";
                throw new IllegalStateException(reason);
            }

            // 步骤 5：发布成功，在数据库中原子更新为 PUBLISHED
            if (!repository.markPublished(claimed)) {
                log.warn("Outbox 发布确认后租约令牌已失效或被覆盖: eventId={}", claimed.eventId());
                return;
            }

            log.info("Outbox 领域事件成功投递: eventId={}, eventType={}, attempt={}",
                    claimed.eventId(), claimed.eventType(), claimed.attempts());
        } catch (Exception exception) {
            // 步骤 6：投递异常处理，记录失败分类并回写重试退避
            handlePublishFailure(claimed, exception);
        }
    }

    /**
     * 处理发送失败与异常回写。
     *
     * @param claimed 消息快照
     * @param exception 发送或确认等待期间产生的异常
     */
    private void handlePublishFailure(ClaimedOutboxMessage claimed, Exception exception) {
        log.warn("Outbox 领域事件投递失败: eventId={}, eventType={}, attempt={}, reason={}",
                claimed.eventId(), claimed.eventType(), claimed.attempts(), exception.getMessage());
        repository.markFailed(claimed, properties.getMaxAttempts(), exception.getClass().getSimpleName());
    }
}
