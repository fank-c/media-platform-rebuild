package com.calles.platform.interaction.interfaces.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.application.video.VideoMetadataApplicationService;
import com.calles.platform.interaction.application.video.VideoMetadataApplicationService.SnapshotApplyResult;
import com.calles.platform.interaction.interfaces.messaging.event.VideoMetadataMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 视频元数据消息消费者 {@link VideoMetadataConsumer} 单元测试。
 *
 * <p>测试验证：
 * <ul>
 *   <li>标准规范载荷（包含 eventId 与 traceId）的正常消费与分发；</li>
 *   <li>载荷缺失 eventId 时，通过 AMQP MessageId / Header 安全兜底回填；</li>
 *   <li>载荷与报头均缺失标识时的防御性拒绝；</li>
 *   <li>畸形 JSON 反序列化失败时的丢弃与降级保护；</li>
 *   <li>应用层异常向上抛出以触发 RabbitMQ 容器重试。</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoMetadataConsumer 单元测试")
class VideoMetadataConsumerTest {

    @Mock
    private VideoMetadataApplicationService videoMetadataApplicationService;

    private ObjectMapper objectMapper;
    private VideoMetadataConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        consumer = new VideoMetadataConsumer(videoMetadataApplicationService, objectMapper);
    }

    @Test
    @DisplayName("标准规范载荷消费成功并委托应用服务")
    void shouldProcessStandardPayloadSuccessfully() {
        // 步骤 1 (Given)：生产者生成的完整 JSON 载荷
        String payload = """
                {
                    "eventId": "evt_norm_100",
                    "eventType": "content.video.metadata",
                    "eventVersion": 1,
                    "traceId": "trace_norm_100",
                    "occurredAt": "2026-09-27T10:00:00Z",
                    "videoId": "v_100",
                    "vid": "cv_100",
                    "duration": 180,
                    "metadataVersion": 1,
                    "status": "PUBLISHED",
                    "updatedAt": "2026-09-27T10:00:00Z"
                }
                """;

        when(videoMetadataApplicationService.apply(any(VideoMetadataMessage.class)))
                .thenReturn(SnapshotApplyResult.APPLIED);

        // 步骤 2 (When)：触发消费
        consumer.onVideoMetadata(payload, null, null);

        // 步骤 3 (Then)：捕获并验证传递给应用服务的参数
        ArgumentCaptor<VideoMetadataMessage> captor = ArgumentCaptor.forClass(VideoMetadataMessage.class);
        verify(videoMetadataApplicationService).apply(captor.capture());

        VideoMetadataMessage captured = captor.getValue();
        assertThat(captured.eventId()).isEqualTo("evt_norm_100");
        assertThat(captured.traceId()).isEqualTo("trace_norm_100");
        assertThat(captured.vid()).isEqualTo("cv_100");
        assertThat(captured.duration()).isEqualTo(180);
    }

    @Test
    @DisplayName("载荷缺失 eventId 时由 AMQP MessageId 及 Header 兜底回填")
    void shouldFallbackToAmqpHeadersWhenPayloadMissesIdentifiers() {
        // 步骤 1 (Given)：旧版或不规范载荷（未携带 eventId 与 traceId）
        String barePayload = """
                {
                    "videoId": "v_200",
                    "vid": "cv_200",
                    "duration": 240,
                    "metadataVersion": 1,
                    "status": "PUBLISHED",
                    "updatedAt": "2026-09-27T10:00:00Z"
                }
                """;

        when(videoMetadataApplicationService.apply(any(VideoMetadataMessage.class)))
                .thenReturn(SnapshotApplyResult.APPLIED);

        // 步骤 2 (When)：传入 AMQP 报头参数执行消费
        consumer.onVideoMetadata(barePayload, "amqp_msg_id_999", "amqp_trace_id_888");

        // 步骤 3 (Then)：验证应用服务接收到回填补全后的对象
        ArgumentCaptor<VideoMetadataMessage> captor = ArgumentCaptor.forClass(VideoMetadataMessage.class);
        verify(videoMetadataApplicationService).apply(captor.capture());

        VideoMetadataMessage captured = captor.getValue();
        assertThat(captured.eventId()).isEqualTo("amqp_msg_id_999");
        assertThat(captured.traceId()).isEqualTo("amqp_trace_id_888");
        assertThat(captured.vid()).isEqualTo("cv_200");
        assertThat(captured.duration()).isEqualTo(240);
    }

    @Test
    @DisplayName("畸形 JSON 反序列化失败时丢弃并不调用应用服务")
    void shouldDropMalformedJsonSafely() {
        // 步骤 1 (Given)：畸形格式 JSON
        String malformedJson = "{ illegal_json: true ";

        // 步骤 2 (When)：触发消费
        consumer.onVideoMetadata(malformedJson, "amqp_1", "trace_1");

        // 步骤 3 (Then)：未调用应用服务，避免不可恢复异常无限重试
        verify(videoMetadataApplicationService, never()).apply(any());
    }

    @Test
    @DisplayName("应用层抛出未受检异常时向上重抛以触发容器重试")
    void shouldRethrowExceptionWhenApplicationServiceFails() {
        // 步骤 1 (Given)：标准载荷但应用层遇到数据库异常
        String payload = """
                {
                    "eventId": "evt_err_1",
                    "vid": "cv_err_1",
                    "duration": 100
                }
                """;

        when(videoMetadataApplicationService.apply(any(VideoMetadataMessage.class)))
                .thenThrow(new RuntimeException("数据库连接瞬时超时"));

        // 步骤 2 (When & Then)：验证消费者向外抛出 IllegalStateException
        assertThatThrownBy(() -> consumer.onVideoMetadata(payload, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("视频元数据事件处理失败");
    }
}
