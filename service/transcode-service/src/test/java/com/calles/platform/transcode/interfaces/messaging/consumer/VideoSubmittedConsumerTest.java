package com.calles.platform.transcode.interfaces.messaging.consumer;

import com.calles.platform.transcode.application.service.TranscodeApplicationService;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.interfaces.messaging.event.VideoSubmittedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 视频提审事件 RabbitMQ 消费者 (VideoSubmittedConsumerTest) 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class VideoSubmittedConsumerTest {

    @Mock private TranscodeApplicationService transcodeApplicationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private VideoSubmittedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new VideoSubmittedConsumer(transcodeApplicationService, objectMapper);
    }

    @Test
    @DisplayName("消费合法提审消息：反序列化成功并在虚拟线程中调度 720P 转码")
    void onVideoSubmitted_success() throws Exception {
        VideoSubmittedMessage message = new VideoSubmittedMessage(
                "video_888", "vid_888", "author_999", "标题测试", "简介测试", "file_origin_777", "cover_666"
        );
        String payload = objectMapper.writeValueAsString(message);

        consumer.onVideoSubmitted(payload);

        // 验证虚拟线程异步调度执行
        verify(transcodeApplicationService, timeout(2000)).processTask(
                eq("video_888"),
                eq("author_999"),
                eq("file_origin_777"),
                eq(QualityPreset.P720)
        );
    }

    @Test
    @DisplayName("消费非法消息：缺少关键属性时静默丢弃，不触发转码流水线")
    void onVideoSubmitted_missingKeyFields_dropsMessage() {
        String invalidPayload = "{\"videoId\":null,\"videoFileId\":null}";

        consumer.onVideoSubmitted(invalidPayload);

        verify(transcodeApplicationService, never()).processTask(any(), any(), any(), any());
    }

    @Test
    @DisplayName("消费损坏载荷：畸形 JSON 不抛出未受控异常并安全拦截")
    void onVideoSubmitted_malformedJson_handlesGracefully() {
        String malformed = "{ broken json content ";

        consumer.onVideoSubmitted(malformed);

        verify(transcodeApplicationService, never()).processTask(any(), any(), any(), any());
    }
}
