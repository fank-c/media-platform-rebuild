package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.application.service.InteractionFeedbackApplicationService;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionVideoActionMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * InteractionVideoActionConsumer 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InteractionVideoActionConsumer 消费者测试")
class InteractionVideoActionConsumerTest {

    @Mock
    private InteractionFeedbackApplicationService interactionFeedbackService;

    private ObjectMapper objectMapper;
    private InteractionVideoActionConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new InteractionVideoActionConsumer(interactionFeedbackService, objectMapper);
    }

    @Test
    @DisplayName("合法报文：正确反序列化并委托应用服务处理")
    void shouldConsumeValidVideoActionMessage() {
        String json = """
                {
                    "eventId": "evt_100",
                    "eventType": "interaction.video-action",
                    "eventVersion": 1,
                    "traceId": "tr_200",
                    "occurredAt": "2026-09-28T03:30:00Z",
                    "payload": {
                        "userId": "user_01",
                        "vid": "cv_123456",
                        "action": "LIKE",
                        "state": "ACTIVE"
                    }
                }
                """;

        consumer.onVideoAction(json);

        verify(interactionFeedbackService).handleInteractionEvent(argThat(msg ->
                msg.eventId().equals("evt_100")
                        && msg.payload().userId().equals("user_01")
                        && msg.payload().vid().equals("cv_123456")
                        && msg.payload().action().equals("LIKE")
                        && "ACTIVE".equals(msg.payload().state())
        ));
    }

    @Test
    @DisplayName("畸形 JSON 报文：反序列化异常时丢弃，不向上抛出异常阻断")
    void shouldDropMalformedJsonWithoutThrowing() {
        String malformedJson = "{ unquoted_key: invalid }";

        consumer.onVideoAction(malformedJson);

        verifyNoInteractions(interactionFeedbackService);
    }

    @Test
    @DisplayName("缺失关键字段时：防御性丢弃，不委托应用服务")
    void shouldDropMessageWhenMissingKeyFields() {
        // 缺少 action
        String jsonMissingAction = """
                {
                    "eventId": "evt_100",
                    "eventType": "interaction.video-action",
                    "payload": {
                        "userId": "user_01",
                        "vid": "cv_123456"
                    }
                }
                """;

        consumer.onVideoAction(jsonMissingAction);

        verifyNoInteractions(interactionFeedbackService);
    }

    @Test
    @DisplayName("应用层处理抛出异常时：消费者重新抛出 RuntimeException 以触发 MQ 重试")
    void shouldRethrowWhenServiceThrows() {
        String json = """
                {
                    "eventId": "evt_100",
                    "eventType": "interaction.video-action",
                    "payload": {
                        "userId": "user_01",
                        "vid": "cv_123456",
                        "action": "LIKE",
                        "state": "ACTIVE"
                    }
                }
                """;
        doThrow(new RuntimeException("数据库临时连接超时")).when(interactionFeedbackService).handleInteractionEvent(any());

        assertThatThrownBy(() -> consumer.onVideoAction(json))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("互动事件消费处理失败: eventId=evt_100");
    }
}
