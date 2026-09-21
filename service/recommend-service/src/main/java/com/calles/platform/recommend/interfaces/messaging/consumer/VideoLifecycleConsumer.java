package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.application.service.CandidateVideoApplicationService;
import com.calles.platform.recommend.config.RecommendMessagingConfiguration;
import com.calles.platform.recommend.interfaces.messaging.event.VideoLifecycleMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 视频生命周期状态变迁 (content.video.offlined / content.video.banned) RabbitMQ 消费者 (VideoLifecycleConsumer)。
 *
 * <p>职责与执行策略：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务消息入站适配层；</li>
 *   <li><b>监听队列</b>：{@link RecommendMessagingConfiguration#VIDEO_LIFECYCLE_QUEUE}；</li>
 *   <li><b>清退下线</b>：接收下架或封禁事件，将候选视频标记为 OFFLINE 或 BANNED，彻底剥夺推荐资格；</li>
 *   <li><b>全链路追踪</b>：提取消息体中的 {@code traceId} 绑定至 MDC。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoLifecycleConsumer {

    private final CandidateVideoApplicationService candidateVideoApplicationService;
    private final ObjectMapper objectMapper;

    /**
     * 监听视频下线或封禁领域事件。
     *
     * @param payload 原始 JSON 字符串载荷
     */
    @RabbitListener(queues = RecommendMessagingConfiguration.VIDEO_LIFECYCLE_QUEUE)
    public void onVideoLifecycleEvent(String payload) {
        log.info("推荐微服务接收到视频生命周期变更事件: payloadLength={}", payload != null ? payload.length() : 0);

        // 步骤 1：反序列化强类型消息对象
        VideoLifecycleMessage message;
        try {
            message = objectMapper.readValue(payload, VideoLifecycleMessage.class);
        } catch (Exception e) {
            log.error("反序列化视频生命周期事件消息失败，丢弃非法消息: payload={}, error={}", payload, e.getMessage(), e);
            return;
        }

        // 步骤 2：防御性非空守卫
        if (message == null || message.videoId() == null || message.videoId().isBlank()) {
            log.warn("丢弃非法或缺失主键 videoId 的生命周期事件: payload={}", payload);
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

            // 步骤 4：根据事件类型分流执行下架或封禁清退
            String eventType = message.eventType() != null ? message.eventType().trim() : "";
            if (RecommendMessagingConfiguration.VIDEO_BANNED_ROUTING_KEY.equalsIgnoreCase(eventType)) {
                candidateVideoApplicationService.handleBanned(message.videoId(), message.vid(), message.reason());
            } else {
                // 默认按创作者主动下线处理
                candidateVideoApplicationService.handleOfflined(message.videoId(), message.vid(), message.reason());
            }
        } catch (Exception e) {
            log.error("推进视频生命周期清退发生未捕获致命异常: videoId={}, error={}", message.videoId(), e.getMessage(), e);
            throw new RuntimeException("视频生命周期消费处理失败: videoId=" + message.videoId(), e);
        } finally {
            MDC.remove("traceId");
        }
    }
}
