package com.calles.platform.auth.infrastructure.outbox;

import com.calles.platform.auth.config.AuthOutboxProperties;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
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
 * Auth Outbox 的统一单条 RabbitMQ 发送器。
 *
 * <p>本类不负责调度、候选扫描或领取租约；快速通道和扫描通道都先通过仓储取得
 * {@link ClaimedOutboxMessage}，再复用此处相同的 Confirm、return 和条件回写状态机。</p>
 */
@Component
public class AuthOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthOutboxPublisher.class);

    /** Outbox 仓储，负责 token 条件状态回写。 */
    private final AuthOutboxRepository repository;
    /** RabbitMQ 发布客户端。 */
    private final RabbitTemplate rabbitTemplate;
    /** 认证事件链路指标。 */
    private final AuthOperationalMetrics metrics;
    /** Outbox 发布运行参数；启动期已完成取值约束校验。 */
    private final AuthOutboxProperties properties;

    /**
     * @param repository Outbox 仓储
     * @param rabbitTemplate RabbitMQ 发布客户端
     * @param metrics 指标出口
     * @param properties 发送与重试参数
     */
    public AuthOutboxPublisher(AuthOutboxRepository repository, RabbitTemplate rabbitTemplate,
            AuthOperationalMetrics metrics, AuthOutboxProperties properties) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * 发送一条已领取消息，只有 Broker ack 且没有 return 才条件更新为 PUBLISHED。
     *
     * <p>发送、Confirm 等待和数据库回写均不在领取事务中；确认超时或回写失败可能造成至少一次
     * 重复投递，消费者必须继续按 eventId 幂等。</p>
     *
     * @param claimed 当前实例持有的发送快照
     */
    public void publish(ClaimedOutboxMessage claimed) {
        CorrelationData correlation = new CorrelationData(claimed.eventId());
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setMessageId(claimed.eventId());
        Message message = new Message(claimed.payload().getBytes(StandardCharsets.UTF_8), messageProperties);
        try {
            // 保持 v1 exchange、routing key、messageId 和数据库原始 payload 不变。
            rabbitTemplate.send("media.platform.events", "auth.account.created.v1", message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(properties.getConfirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException(confirm.getReason() == null ? "消息不可路由" : confirm.getReason());
            }
            // 旧 token 不能覆盖新领取者；丢失领取权只记录，不将消息改回失败。
            if (!repository.markPublished(claimed)) {
                metrics.recordOutboxClaim("lost");
                LOGGER.warn("Outbox 发布确认后的领取令牌已失效，eventId={}", claimed.eventId());
                return;
            }
            metrics.recordOutboxPublished();
        } catch (Exception exception) {
            handlePublishFailure(claimed, exception);
        }
    }

    /**
     * 以当前领取令牌登记失败或丢失领取权；不把异常详情写入数据库。
     *
     * @param claimed 当前实例领取的消息快照
     * @param exception RabbitMQ 或确认阶段异常
     */
    private void handlePublishFailure(ClaimedOutboxMessage claimed, Exception exception) {
        LOGGER.warn("账号创建事件发布失败，eventId={}，attempt={}，reason={}", claimed.eventId(),
                claimed.attempts(), exception.getClass().getSimpleName());
        // token 不匹配说明新领取者已接管，旧工作线程只能观测，不能覆盖状态。
        if (repository.markFailed(claimed, properties.getMaxAttempts(), exception.getClass().getSimpleName())) {
            metrics.recordOutboxFailed();
        } else {
            metrics.recordOutboxClaim("lost");
        }
    }
}
