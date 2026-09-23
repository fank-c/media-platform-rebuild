package com.calles.platform.user.interfaces.messaging.consumer;

import com.calles.platform.common.core.event.EventEnvelope;
import com.calles.platform.user.application.profile.AccountCreatedEventProcessor;
import com.calles.platform.user.application.profile.AccountCreatedPayloadV1;
import com.calles.platform.user.exception.InvalidAccountCreatedEventException;
import com.calles.platform.user.infrastructure.observability.UserOperationalMetrics;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 账号创建事件适配器；业务事务由处理器负责，非法消息直接进入死信出口。
 */
@Component
@ConditionalOnProperty(name = "user.messaging.account-created.enabled", havingValue = "true", matchIfMissing = true)
public class AccountCreatedConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountCreatedConsumer.class);
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** 账号创建事件事务处理器。 */
    private final AccountCreatedEventProcessor processor;
    /** 消费失败尝试指标。 */
    private final UserOperationalMetrics metrics;
    /** 账号创建 v1 协议解码器，确保原始消息只解析一次。 */
    private final AccountCreatedEventDecoder decoder;

    /**
     * 创建消息消费者。
     *
     * @param processor 账号创建事件事务处理器
     * @param metrics 消费结果指标
     * @param decoder 账号创建 v1 协议解码器
     */
    public AccountCreatedConsumer(AccountCreatedEventProcessor processor, UserOperationalMetrics metrics,
            AccountCreatedEventDecoder decoder) {
        this.processor = processor;
        this.metrics = metrics;
        this.decoder = decoder;
    }

    /**
     * 消费原始消息；临时异常交给容器有限重试，非法契约拒绝且不立即重新入队。
     *
     * @param message RabbitMQ 原始消息，消息体不得写入日志
     */
    @RabbitListener(queues = "user-service.auth-account-created.v1",
            concurrency = "${user.messaging.account-created.concurrency:1}")
    public void consume(Message message) {
        String previousTraceId = MDC.get("traceId");
        MDC.put("traceId", fallbackTraceId(message));
        try {
            // 解析器一次性完成 JSON 绑定和 v1 校验；处理器直接接收信封与本地载荷。
            EventEnvelope<AccountCreatedPayloadV1> event = decoder.decode(message.getBody());
            String eventTraceId = decoder.resolveTraceId(event);
            if (eventTraceId != null) {
                MDC.put("traceId", eventTraceId);
            }
            AccountCreatedEventProcessor.ProcessResult result = processor.process(event);
            // 处理器返回时事务代理已经完成提交，仅在提交成功后累计成功或重复指标。
            if (result == AccountCreatedEventProcessor.ProcessResult.DUPLICATE) {
                metrics.recordEventDuplicate();
            } else {
                metrics.recordEventProcessed(result.name());
            }
            LOGGER.info("账号创建事件处理完成，messageId={}，result={}",
                    message.getMessageProperties().getMessageId(), result);
        } catch (InvalidAccountCreatedEventException exception) {
            metrics.recordEventFailed();
            LOGGER.warn("拒绝非法账号创建事件，messageId={}，reason={}",
                    message.getMessageProperties().getMessageId(), exception.getMessage());
            throw new AmqpRejectAndDontRequeueException(exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            // 临时错误仍交由监听容器按配置重试，指标按实际失败尝试累计。
            metrics.recordEventFailed();
            throw exception;
        } finally {
            restoreTraceId(previousTraceId);
        }
    }

    /**
     * 不读取 JSON 即为当前消费尝试建立安全的临时 traceId，非法消息也可关联受控日志。
     *
     * @param message RabbitMQ 原始消息
     * @return 由合法 messageId 或随机 UUID 构成的安全追踪标识
     */
    private String fallbackTraceId(Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        return messageId != null && SAFE_TRACE_ID.matcher(messageId).matches()
                ? messageId : UUID.randomUUID().toString();
    }

    /**
     * 恢复监听线程进入本次消费前的追踪上下文，避免线程复用污染后续消息日志。
     *
     * @param previousTraceId 消费开始前的 traceId；{@code null} 表示原先不存在
     */
    private void restoreTraceId(String previousTraceId) {
        if (previousTraceId == null) {
            MDC.remove("traceId");
        } else {
            MDC.put("traceId", previousTraceId);
        }
    }
}
