package com.calles.platform.audit.interfaces.messaging.consumer;

import com.calles.platform.audit.application.coordinator.AuditTaskCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 视频提审消息消费者 {@link VideoSubmittedConsumer} 单元测试用例。
 *
 * <p>测试目标：验证强类型绑定模型在信封结构、扁平报文、未知向前兼容扩展属性及异常数据下的鲁棒性。</p>
 */
@ExtendWith(MockitoExtension.class)
class VideoSubmittedConsumerTest {

    @Mock
    private AuditTaskCoordinator auditTaskCoordinator;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private VideoSubmittedConsumer consumer;

    @Test
    @DisplayName("消费标准 EventEnvelope 嵌套 payload 格式消息成功解析并触发协调器")
    void shouldConsumeStandardEnvelopeMessage() {
        // Given (准备携带标准信封与未知字段的事件报文)
        String jsonPayload = """
                {
                    "eventId": "evt-123456",
                    "eventType": "content.video.submitted",
                    "traceId": "trace-9876",
                    "futureField": "compatible_unknown_value",
                    "payload": {
                        "videoId": "v_test_001",
                        "vid": "cv_0123456789012345678901",
                        "authorId": "u_creator",
                        "title": "测试视频标题",
                        "description": "测试视频简介",
                        "coverFileId": "f_cover_001",
                        "videoFileId": "f_video_001",
                        "duration": 120,
                        "extraMetadata": {"codec": "h264"}
                    }
                }
                """;

        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), new MessageProperties());

        // When (执行消费者处理)
        consumer.onVideoSubmitted(message);

        // Then (验证协调器收到正确的入参，包括视频时长)
        verify(auditTaskCoordinator).processVideoSubmission(
                eq("v_test_001"),
                eq("cv_0123456789012345678901"),
                eq("u_creator"),
                eq("测试视频标题"),
                eq("测试视频简介"),
                eq("f_cover_001"),
                eq("f_video_001"),
                eq(120)
        );
    }

    @Test
    @DisplayName("消费扁平无信封包装的直接 Payload 消息成功解析并触发协调器")
    void shouldConsumeFlatPayloadMessage() {
        // Given (准备直接在顶层声明属性的扁平报文)
        String jsonPayload = """
                {
                    "videoId": "v_flat_100",
                    "vid": "cv_flat_100",
                    "authorId": "u_creator_flat",
                    "title": "扁平测试标题",
                    "description": "扁平测试简介",
                    "coverFileId": "f_cover_flat",
                    "videoFileId": "f_video_flat",
                    "duration": 60,
                    "traceId": "trace-flat-111"
                }
                """;

        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), new MessageProperties());

        // When (执行消费者处理)
        consumer.onVideoSubmitted(message);

        // Then (验证协调器成功接收扁平字段与时长)
        verify(auditTaskCoordinator).processVideoSubmission(
                eq("v_flat_100"),
                eq("cv_flat_100"),
                eq("u_creator_flat"),
                eq("扁平测试标题"),
                eq("扁平测试简介"),
                eq("f_cover_flat"),
                eq("f_video_flat"),
                eq(60)
        );
    }

    @Test
    @DisplayName("提审消息缺失关键字段 videoId 时拦截拒绝消费，不调用协调器")
    void shouldIgnoreMessageWhenVideoIdIsMissing() {
        // Given (准备缺少 videoId 的报文)
        String jsonPayload = """
                {
                    "eventId": "evt-empty-id",
                    "payload": {
                        "authorId": "u_creator",
                        "title": "缺失视频ID"
                    }
                }
                """;

        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), new MessageProperties());

        // When (执行消费者处理)
        consumer.onVideoSubmitted(message);

        // Then (断言协调器未被触发)
        verifyNoInteractions(auditTaskCoordinator);
    }

    @Test
    @DisplayName("消息格式损坏非法时抛出 RuntimeException 触发 RabbitMQ 重试或死信")
    void shouldThrowWhenMessageIsMalformed() {
        // Given (准备损坏的非 JSON 文本)
        String malformedJson = "this is not a valid json text";
        Message message = new Message(malformedJson.getBytes(StandardCharsets.UTF_8), new MessageProperties());

        // When & Then (断言抛出受控异常)
        assertThatThrownBy(() -> consumer.onVideoSubmitted(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("提审事件消费处理失败，触发重试");

        verifyNoInteractions(auditTaskCoordinator);
    }
}
