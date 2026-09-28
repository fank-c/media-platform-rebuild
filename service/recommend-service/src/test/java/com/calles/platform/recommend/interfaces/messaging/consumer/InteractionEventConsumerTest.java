package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.interfaces.messaging.dispatcher.InteractionEventDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * InteractionEventConsumer 统一消费者单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InteractionEventConsumer 统一消费者测试")
class InteractionEventConsumerTest {

    @Mock
    private InteractionEventDispatcher dispatcher;

    private ObjectMapper objectMapper;
    private InteractionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new InteractionEventConsumer(dispatcher, objectMapper);
    }

    @Test
    @DisplayName("合法报文：正确提取信封元数据、贯通 MDC 并委托分发器处理，最后清理 MDC")
    void shouldConsumeValidInteractionEvent() throws Exception {
        String json = """
                {
                    "eventId": "evt_unified_100",
                    "eventType": "interaction.author-action",
                    "eventVersion": 1,
                    "traceId": "tr_trace_100",
                    "occurredAt": "2026-09-28T09:30:00Z",
                    "payload": {
                        "userId": "user_01",
                        "authorId": "author_02",
                        "action": "FOLLOW",
                        "state": "ACTIVE"
                    }
                }
                """;

        // 在 dispatcher 被调用时验证 MDC 是否绑定了 traceId
        doAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo("tr_trace_100");
            return null;
        }).when(dispatcher).dispatch(eq("interaction.author-action"), anyString());

        consumer.onInteractionEvent(json);

        verify(dispatcher).dispatch(eq("interaction.author-action"), eq(json));
        // 验证 finally 块中清理了 MDC
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("traceId 缺省时：自动回退至 eventId 作为 traceId 贯通日志")
    void shouldFallbackTraceIdToEventIdWhenMissing() throws Exception {
        String json = """
                {
                    "eventId": "evt_unified_200",
                    "eventType": "interaction.video-action",
                    "eventVersion": 1,
                    "payload": {
                        "userId": "user_01",
                        "vid": "cv_100",
                        "action": "LIKE"
                    }
                }
                """;

        doAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isEqualTo("evt_unified_200");
            return null;
        }).when(dispatcher).dispatch(eq("interaction.video-action"), anyString());

        consumer.onInteractionEvent(json);

        verify(dispatcher).dispatch(eq("interaction.video-action"), eq(json));
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("畸形 JSON 报文：反序列化异常时丢弃，不向上抛出异常阻断队列")
    void shouldDropMalformedJsonWithoutThrowing() throws Exception {
        String malformedJson = "{ unquoted_key: invalid }";

        consumer.onInteractionEvent(malformedJson);

        verifyNoInteractions(dispatcher);
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("缺失关键信封字段 (eventId/eventType/payload)：防御性丢弃，不委托分发器")
    void shouldDropMessageWhenMissingEnvelopeFields() throws Exception {
        // 缺少 eventType
        String jsonMissingEventType = """
                {
                    "eventId": "evt_unified_300",
                    "eventVersion": 1,
                    "payload": {
                        "userId": "user_01"
                    }
                }
                """;

        consumer.onInteractionEvent(jsonMissingEventType);
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("不支持的契约版本 (eventVersion != 1)：防御性丢弃")
    void shouldDropMessageWhenVersionUnsupported() throws Exception {
        String jsonUnsupportedVersion = """
                {
                    "eventId": "evt_unified_400",
                    "eventType": "interaction.video-action",
                    "eventVersion": 2,
                    "payload": {
                        "userId": "user_01"
                    }
                }
                """;

        consumer.onInteractionEvent(jsonUnsupportedVersion);
        verifyNoInteractions(dispatcher);
    }

    /** 公共信封只接受当前明确声明的契约，缺失或类型错误不得落库。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"eventId\":null,\"eventType\":\"interaction.video-action\",\"eventVersion\":1,\"payload\":{}}",
            "{\"eventId\":\"e1\",\"eventType\":\"interaction.video-action\",\"payload\":{}}",
            "{\"eventId\":\"e1\",\"eventType\":\"interaction.video-action\",\"eventVersion\":1.5,\"payload\":{}}"
    })
    void shouldRejectInvalidEnvelope(String json) {
        consumer.onInteractionEvent(json);
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("分发器处理抛出异常时：消费者包装为 RuntimeException 重新抛出以触发 MQ 重试，且清理 MDC")
    void shouldRethrowWhenDispatcherThrows() throws Exception {
        String json = """
                {
                    "eventId": "evt_unified_500",
                    "eventType": "interaction.video-action",
                    "eventVersion": 1,
                    "payload": {
                        "userId": "user_01",
                        "vid": "cv_100",
                        "action": "LIKE"
                    }
                }
                """;
        doThrow(new RuntimeException("数据库临时连接中断")).when(dispatcher).dispatch(anyString(), anyString());

        assertThatThrownBy(() -> consumer.onInteractionEvent(json))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("交互事件消费处理失败: eventId=evt_unified_500");

        assertThat(MDC.get("traceId")).isNull();
    }
}
