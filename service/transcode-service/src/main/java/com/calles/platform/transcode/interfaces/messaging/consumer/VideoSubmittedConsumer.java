package com.calles.platform.transcode.interfaces.messaging.consumer;

import com.calles.platform.transcode.application.service.TranscodeApplicationService;
import com.calles.platform.transcode.config.TranscodeMessagingConfiguration;
import com.calles.platform.transcode.domain.model.QualityPreset;
import com.calles.platform.transcode.interfaces.messaging.event.VideoSubmittedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 视频提审发布领域事件 (content.video.submitted) RabbitMQ 消费者 (VideoSubmittedConsumer)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：入站适配层 (interfaces/messaging)，专门监听 MQ 提审事件；</li>
 *   <li><b>消费队列</b>：{@link TranscodeMessagingConfiguration#TRANSCODE_QUEUE}；</li>
 *   <li><b>非阻塞解耦</b>：解析合法事件后，立即在 Java 21 虚拟线程中异步推进压制流水线，避免长耗时阻塞 RabbitMQ 监听器信道；</li>
 *   <li><b>阶段策略</b>：阶段一默认触发 720P 高清切片转码，驱动下游内容服务发布门禁闭环。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoSubmittedConsumer {

    private final TranscodeApplicationService transcodeApplicationService;
    private final ObjectMapper objectMapper;

    /**
     * 监听提审事件，解析后派发至虚拟线程调度转码。
     *
     * @param payload 原始 JSON 字符串载荷
     */
    @RabbitListener(queues = TranscodeMessagingConfiguration.TRANSCODE_QUEUE)
    public void onVideoSubmitted(String payload) {
        log.info("接收到视频提审事件消息: payload={}", payload);
        try {
            VideoSubmittedMessage message = objectMapper.readValue(payload, VideoSubmittedMessage.class);

            if (message == null || message.videoId() == null || message.videoFileId() == null) {
                log.warn("丢弃非法或缺少关键标识的视频提审消息: {}", payload);
                return;
            }

            // 在 Java 21 虚拟线程中异步调度转码任务，杜绝阻塞 RabbitMQ 消费者通道
            Thread.ofVirtual().name("transcode-worker-" + message.videoId()).start(() -> {
                try {
                    transcodeApplicationService.processTask(
                            message.videoId(),
                            message.authorId(),
                            message.videoFileId(),
                            QualityPreset.P720
                    );
                } catch (Exception e) {
                    log.error("虚拟线程调度转码流水线异常: videoId={}, error={}", message.videoId(), e.getMessage(), e);
                }
            });

        } catch (Exception e) {
            log.error("反序列化视频提审事件消息失败: payload={}, error={}", payload, e.getMessage(), e);
        }
    }
}
