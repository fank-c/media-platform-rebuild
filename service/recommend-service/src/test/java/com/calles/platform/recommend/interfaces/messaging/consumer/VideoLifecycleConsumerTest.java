package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.application.service.CandidateVideoApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * VideoLifecycleConsumer 视频下架与封禁生命周期消费者单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VideoLifecycleConsumer 消息消费测试")
class VideoLifecycleConsumerTest {

    @Mock
    private CandidateVideoApplicationService candidateVideoApplicationService;

    private ObjectMapper objectMapper;

    private VideoLifecycleConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        consumer = new VideoLifecycleConsumer(candidateVideoApplicationService, objectMapper);
    }

    @Test
    @DisplayName("onVideoLifecycleEvent：消费下架事件触发 handleOfflined")
    void shouldConsumeOfflinedEventSuccessfully() {
        String payload = """
                {
                  "eventId": "evt_off_1",
                  "eventType": "content.video.offlined",
                  "traceId": "trace_off",
                  "videoId": "vid_100",
                  "vid": "cv_abc",
                  "reason": "创作者隐藏"
                }
                """;

        consumer.onVideoLifecycleEvent(payload);

        verify(candidateVideoApplicationService).handleOfflined(eq("vid_100"), eq("cv_abc"), eq("创作者隐藏"));
    }

    @Test
    @DisplayName("onVideoLifecycleEvent：消费封禁事件触发 handleBanned")
    void shouldConsumeBannedEventSuccessfully() {
        String payload = """
                {
                  "eventId": "evt_ban_1",
                  "eventType": "content.video.banned",
                  "traceId": "trace_ban",
                  "videoId": "vid_200",
                  "vid": "cv_xyz",
                  "reason": "违规色情低俗"
                }
                """;

        consumer.onVideoLifecycleEvent(payload);

        verify(candidateVideoApplicationService).handleBanned(eq("vid_200"), eq("cv_xyz"), eq("违规色情低俗"));
    }

    @Test
    @DisplayName("onVideoLifecycleEvent：缺少 videoId 守卫丢弃")
    void shouldDropWhenVideoIdMissing() {
        String payload = """
                {
                  "eventId": "evt_ban_1",
                  "eventType": "content.video.banned"
                }
                """;

        consumer.onVideoLifecycleEvent(payload);

        verify(candidateVideoApplicationService, never()).handleBanned(any(), any(), any());
        verify(candidateVideoApplicationService, never()).handleOfflined(any(), any(), any());
    }

    @Test
    @DisplayName("onVideoLifecycleEvent：异常时抛出以触发 MQ 重试")
    void shouldRethrowWhenServiceFails() {
        String payload = """
                {
                  "eventId": "evt_off_1",
                  "eventType": "content.video.offlined",
                  "videoId": "vid_100",
                  "vid": "cv_abc"
                }
                """;

        doThrow(new RuntimeException("DB Connection failed"))
                .when(candidateVideoApplicationService).handleOfflined(any(), any(), any());

        assertThatThrownBy(() -> consumer.onVideoLifecycleEvent(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("视频生命周期消费处理失败");
    }
}
