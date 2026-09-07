package com.calles.platform.user.interfaces.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.common.core.event.EventEnvelope;
import com.calles.platform.user.application.event.AccountCreatedPayloadV1;
import com.calles.platform.user.application.event.AccountCreatedEventProcessor;
import com.calles.platform.user.exception.InvalidAccountCreatedEventException;
import com.calles.platform.user.infrastructure.observability.UserOperationalMetrics;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** RabbitMQ 账号创建事件适配器的失败分类、指标与 MDC 恢复测试。 */
@ExtendWith(MockitoExtension.class)
class AccountCreatedConsumerTest {

    @Mock private AccountCreatedEventProcessor processor;
    @Mock private UserOperationalMetrics metrics;
    @Mock private AccountCreatedEventDecoder decoder;

    /** 每个用例后清理当前线程 MDC，避免测试之间相互污染。 */
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** 成功路径应在处理期间使用消息 traceId，结束后恢复原有上下文。 */
    @Test
    void usesDecodedTraceIdAndRestoresExistingMdcAfterSuccess() {
        AccountCreatedConsumer consumer = new AccountCreatedConsumer(processor, metrics, decoder);
        EventEnvelope<AccountCreatedPayloadV1> event = event();
        MDC.put("traceId", "outer-trace");
        when(decoder.decode(any())).thenReturn(event);
        when(decoder.resolveTraceId(event)).thenReturn("message-trace");
        doAnswer(invocation -> {
            assertEquals("message-trace", MDC.get("traceId"));
            return AccountCreatedEventProcessor.ProcessResult.CREATED;
        }).when(processor).process(event);

        consumer.consume(message("message-001"));

        verify(metrics).recordEventProcessed("CREATED");
        assertEquals("outer-trace", MDC.get("traceId"));
    }

    /** 协议错误必须拒绝且不立即重新入队，异常出口同样恢复原 MDC。 */
    @Test
    void rejectsInvalidEventAndRestoresExistingMdc() {
        AccountCreatedConsumer consumer = new AccountCreatedConsumer(processor, metrics, decoder);
        MDC.put("traceId", "outer-trace");
        when(decoder.decode(any())).thenThrow(new InvalidAccountCreatedEventException("字段错误"));

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.consume(message("message-002")));

        verify(metrics).recordEventFailed();
        assertEquals("outer-trace", MDC.get("traceId"));
    }

    /** 资料初始化等临时异常继续抛给监听容器，从而保留既有有限重试行为。 */
    @Test
    void rethrowsTemporaryFailureAndClearsNewMdc() {
        AccountCreatedConsumer consumer = new AccountCreatedConsumer(processor, metrics, decoder);
        EventEnvelope<AccountCreatedPayloadV1> event = event();
        when(decoder.decode(any())).thenReturn(event);
        when(processor.process(event)).thenThrow(new IllegalStateException("数据库暂时不可用"));

        assertThrows(IllegalStateException.class, () -> consumer.consume(message("message-003")));

        verify(metrics).recordEventFailed();
        assertNullTraceId();
    }

    /** 构造已由 Decoder 产生的最小类型化事件。 */
    private EventEnvelope<AccountCreatedPayloadV1> event() {
        return eventWithTraceId(null);
    }

    /** 构造不依赖 auth-service 类型的本地 v1 事件样本。 */
    private EventEnvelope<AccountCreatedPayloadV1> eventWithTraceId(String traceId) {
        return new EventEnvelope<>("550e8400-e29b-41d4-a716-446655440000",
                "auth.account.created", 1, null, null, "0123456789abcdef0123456789abcdef", traceId,
                new AccountCreatedPayloadV1("0123456789abcdef0123456789abcdef", "user", null));
    }

    /** 构造无敏感内容的 RabbitMQ 原始消息。 */
    private Message message(String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }

    /** 断言消费前没有上下文时，finally 会移除本次创建的 traceId。 */
    private void assertNullTraceId() {
        assertNull(MDC.get("traceId"));
    }
}
