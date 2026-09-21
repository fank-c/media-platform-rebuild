package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.application.service.CandidateVideoApplicationService;
import com.calles.platform.recommend.config.RecommendMessagingConfiguration;
import com.calles.platform.recommend.interfaces.messaging.event.VideoPublishedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 视频正式发布上线事件 (content.video.published) RabbitMQ 消费者 (VideoPublishedConsumer)。
 *
 * <p>职责与执行策略：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务消息入站适配层；</li>
 *   <li><b>监听队列</b>：{@link RecommendMessagingConfiguration#VIDEO_PUBLISHED_QUEUE}；</li>
 *   <li><b>入池处理</b>：委托应用服务将新作品幂等准入推荐候选池；</li>
 *   <li><b>全链路追踪</b>：提取消息体中的 {@code traceId} 绑定至 MDC。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoPublishedConsumer {

    private final CandidateVideoApplicationService candidateVideoApplicationService;
    private final ObjectMapper objectMapper;

    /**
     * 监听视频公开发布领域事件。
     *
     * @param payload 原始 JSON 字符串载荷
     */
    @RabbitListener(queues = RecommendMessagingConfiguration.VIDEO_PUBLISHED_QUEUE)
    public void onVideoPublished(String payload) {
        log.info("推荐微服务接收到视频公开发布入池事件: payloadLength={}", payload != null ? payload.length() : 0);

        // 步骤 1：反序列化强类型消息对象
        VideoPublishedMessage message;
        try {
            message = objectMapper.readValue(payload, VideoPublishedMessage.class);
        } catch (Exception e) {
            log.error("反序列化视频发布入池事件消息失败，丢弃非法消息: payload={}, error={}", payload, e.getMessage(), e);
            return;
        }

        // 步骤 2：防御性非空守卫
        if (message == null || message.videoId() == null || message.videoId().isBlank()) {
            log.warn("丢弃非法或缺失主键 videoId 的发布入池事件: payload={}", payload);
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

            // 步骤 4：推进候选池入库
            candidateVideoApplicationService.handlePublished(message);
        } catch (Exception e) {
            log.error("推进视频进入推荐候选池发生未捕获致命异常: videoId={}, error={}", message.videoId(), e.getMessage(), e);
            throw new RuntimeException("视频发布入池消费处理失败: videoId=" + message.videoId(), e);
        } finally {
            MDC.remove("traceId");
        }
    }
}
