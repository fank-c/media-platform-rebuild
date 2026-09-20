package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.application.service.VideoVectorApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 视频提审事件消费者单元测试。
 */
@ExtendWith(MockitoExtension.class)
class VideoSubmittedConsumerTest {

    @Mock
    private VideoVectorApplicationService videoVectorApplicationService;

    private ObjectMapper objectMapper;
    private VideoSubmittedConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new VideoSubmittedConsumer(videoVectorApplicationService, objectMapper);
    }

    @Test
    @DisplayName("正确消费并解析合法的 content.video.submitted 提审消息并同步推进流水线")
    void shouldConsumeValidMessage() {
        String json = """
                {
                    "eventId": "ev-100",
                    "eventType": "content.video.submitted",
                    "traceId": "tr-200",
                    "videoId": "vid-300",
                    "vid": "cv012345",
                    "authorId": "auth-50",
                    "title": "测试视频标题",
                    "description": "测试视频简介"
                }
                """;

        consumer.onVideoSubmitted(json);

        verify(videoVectorApplicationService).processVideoEmbedding(
                eq("vid-300"),
                eq("cv012345"),
                eq("auth-50"),
                eq("测试视频标题"),
                eq("测试视频简介")
        );
    }

    @Test
    @DisplayName("收到缺失 videoId 的非法消息时直接丢弃不触发应用层调用")
    void shouldDropInvalidMessage() {
        String json = """
                {
                    "eventId": "ev-100",
                    "eventType": "content.video.submitted"
                }
                """;

        consumer.onVideoSubmitted(json);

        verify(videoVectorApplicationService, never()).processVideoEmbedding(
                anyString(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    @DisplayName("流水线执行出现致命未捕获异常时向上抛出 RuntimeException 触发 MQ 重试")
    void shouldThrowRuntimeExceptionWhenApplicationServiceFails() {
        String json = """
                {
                    "eventId": "ev-101",
                    "eventType": "content.video.submitted",
                    "videoId": "vid-fatal-1",
                    "vid": "cv012346",
                    "authorId": "auth-50",
                    "title": "测试",
                    "description": "测试"
                }
                """;

        org.mockito.Mockito.doThrow(new RuntimeException("数据库连接不可用"))
                .when(videoVectorApplicationService)
                .processVideoEmbedding(anyString(), anyString(), anyString(), anyString(), anyString());

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> consumer.onVideoSubmitted(json)
        );
    }
}
