package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.application.service.VideoVectorApplicationService;
import com.calles.platform.recommend.config.RecommendMessagingConfiguration;
import com.calles.platform.recommend.interfaces.messaging.event.VideoSubmittedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 视频提审发布事件 (content.video.submitted) RabbitMQ 消费者 (VideoSubmittedConsumer)。
 *
 * <p>职责与执行策略：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务消息入站适配层；</li>
 *   <li><b>监听队列</b>：{@link RecommendMessagingConfiguration#VIDEO_SUBMITTED_QUEUE}；</li>
 *   <li><b>异步非阻塞</b>：解析合法事件后，立即派发至 Java 21 虚拟线程执行向量计算与库表写入，
 *       杜绝阻塞 RabbitMQ AMQP 消费者通道；</li>
 *   <li><b>全链路追踪</b>：提取消息体中的 {@code traceId} 绑定至 MDC。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoSubmittedConsumer {

    private final VideoVectorApplicationService videoVectorApplicationService;
    private final ObjectMapper objectMapper;

    /**
     * 监听视频提审发布领域事件。
     *
     * @param payload 原始 JSON 字符串载荷
     */
    @RabbitListener(queues = RecommendMessagingConfiguration.VIDEO_SUBMITTED_QUEUE)
    public void onVideoSubmitted(String payload) {
        log.info("推荐微服务接收到视频提审事件: payloadLength={}", payload != null ? payload.length() : 0);

        // 步骤 1：反序列化强类型消息对象
        VideoSubmittedMessage message;
        try {
            message = objectMapper.readValue(payload, VideoSubmittedMessage.class);
        } catch (Exception e) {
            log.error("反序列化视频提审事件消息失败，丢弃非法消息: payload={}, error={}", payload, e.getMessage(), e);
            return;
        }

        // 步骤 2：防御性非空守卫
        if (message == null || message.videoId() == null || message.videoId().isBlank()) {
            log.warn("丢弃非法或缺失主键 videoId 的提审事件: payload={}", payload);
            return;
        }

        // 步骤 3：绑定 traceId 全链路追踪上下文
        String traceId = message.traceId() != null && !message.traceId().isBlank()
                ? message.traceId()
                : message.eventId();

        try {
            if (traceId != null && !traceId.isBlank()) {
                MDC.put("traceId", traceId);
            }

            // 步骤 4：在 Spring 容器托管的虚拟线程中推进向量化流水线
            videoVectorApplicationService.processVideoEmbedding(
                    message.videoId(),
                    message.vid(),
                    message.authorId(),
                    message.title(),
                    message.description()
            );
        } catch (Exception e) {
            log.error("推进视频特征向量化流水线发生未捕获致命异常: videoId={}, error={}", message.videoId(), e.getMessage(), e);
            throw new RuntimeException("视频特征向量化消费处理失败: videoId=" + message.videoId(), e);
        } finally {
            MDC.remove("traceId");
        }
    }
}
