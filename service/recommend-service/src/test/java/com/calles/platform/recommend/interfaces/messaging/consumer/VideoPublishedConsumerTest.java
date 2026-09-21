package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.application.service.CandidateVideoApplicationService;
import com.calles.platform.recommend.interfaces.messaging.event.VideoPublishedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * VideoPublishedConsumer 视频发布入池消费者单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoPublishedConsumer 消息消费测试")
class VideoPublishedConsumerTest {

    @Mock
    private CandidateVideoApplicationService candidateVideoApplicationService;

    private ObjectMapper objectMapper;

    private VideoPublishedConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        consumer = new VideoPublishedConsumer(candidateVideoApplicationService, objectMapper);
    }

    @Test
    @DisplayName("onVideoPublished：成功反序列化并触发应用服务入池")
    void shouldConsumePublishedEventSuccessfully() {
        String payload = """
                {
                  "eventId": "evt_100",
                  "eventType": "content.video.published",
                  "traceId": "trace_100",
                  "videoId": "vid_100",
                  "vid": "cv_abc123",
                  "authorId": "author_01",
                  "domainTagIds": "tag_dom_01",
                  "topicTagIds": "tag_top_01"
                }
                """;

        consumer.onVideoPublished(payload);

        verify(candidateVideoApplicationService).handlePublished(any(VideoPublishedMessage.class));
    }

    @Test
    @DisplayName("onVideoPublished：非法 JSON 载荷静默丢弃不抛出异常阻断")
    void shouldDropMalformedJsonSilently() {
        consumer.onVideoPublished("invalid-json-content");

        verify(candidateVideoApplicationService, never()).handlePublished(any());
    }

    @Test
    @DisplayName("onVideoPublished：缺少 videoId 守卫丢弃")
    void shouldDropWhenVideoIdMissing() {
        String payload = """
                {
                  "eventId": "evt_100",
                  "eventType": "content.video.published",
                  "vid": "cv_abc123"
                }
                """;

        consumer.onVideoPublished(payload);

        verify(candidateVideoApplicationService, never()).handlePublished(any());
    }

    @Test
    @DisplayName("onVideoPublished：应用层异常时向上抛出 RuntimeException 以触发 MQ 重试")
    void shouldRethrowWhenServiceFails() {
        String payload = """
                {
                  "eventId": "evt_100",
                  "eventType": "content.video.published",
                  "videoId": "vid_100",
                  "vid": "cv_abc123",
                  "authorId": "author_01"
                }
                """;

        doThrow(new RuntimeException("DB Connection failed"))
                .when(candidateVideoApplicationService).handlePublished(any());

        assertThatThrownBy(() -> consumer.onVideoPublished(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("视频发布入池消费处理失败");
    }
}
