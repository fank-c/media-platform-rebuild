package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.application.service.InteractionFeedbackApplicationService;
import com.calles.platform.recommend.config.RecommendMessagingConfiguration;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionVideoActionMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 互动视频行为事件 (interaction.video-action) RabbitMQ 消费者 (InteractionVideoActionConsumer)。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>监听队列</b>：{@link RecommendMessagingConfiguration#INTERACTION_VIDEO_ACTION_QUEUE}；</li>
 *   <li><b>反序列化与安全校验</b>：反序列化 {@link InteractionVideoActionMessage}，防御空值与残缺消息；</li>
 *   <li><b>追踪贯通</b>：提取 {@code traceId} 绑定至 MDC，确保日志链路完整；</li>
 *   <li><b>异常重试</b>：捕获业务异常并重新抛出，驱动 Spring AMQP 容器进行退避重试。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionVideoActionConsumer {

    private final InteractionFeedbackApplicationService interactionFeedbackService;
    private final ObjectMapper objectMapper;

    /**
     * 监听并消费互动视频行为领域事件。
     *
     * @param payload 原始 JSON 报文字符串
     */
    @RabbitListener(queues = RecommendMessagingConfiguration.INTERACTION_VIDEO_ACTION_QUEUE)
    public void onVideoAction(String payload) {
        log.info("推荐服务接收互动视频行为事件: payloadLength={}", payload != null ? payload.length() : 0);

        // 步骤 1：反序列化；畸形 JSON 属于不可恢复格式错误，直接丢弃避免死信风暴
        InteractionVideoActionMessage message;
        try {
            message = objectMapper.readValue(payload, InteractionVideoActionMessage.class);
        } catch (Exception e) {
            log.error("反序列化互动事件失败，丢弃非法消息: payload={}, error={}", payload, e.getMessage(), e);
            return;
        }

        // 步骤 2：核心字段完整性防御校验
        if (message == null || message.payload() == null
                || message.eventId() == null || message.eventId().isBlank()
                || message.payload().userId() == null || message.payload().userId().isBlank()
                || message.payload().vid() == null || message.payload().vid().isBlank()
                || message.payload().action() == null || message.payload().action().isBlank()) {
            log.warn("丢弃缺失关键主键或行为标识的互动事件: payload={}", payload);
            return;
        }

        // 步骤 3：绑定全链路日志追踪 ID
        String traceId = (message.traceId() != null && !message.traceId().isBlank())
                ? message.traceId()
                : message.eventId();

        try {
            MDC.put("traceId", traceId);

            // 步骤 4：委托应用层执行幂等消费与画像演进
            interactionFeedbackService.handleInteractionEvent(message);
        } catch (Exception e) {
            log.error("处理互动事件发生未捕获异常，向上抛出触发 MQ 重试: eventId={}, userId={}, vid={}, action={}, error={}",
                    message.eventId(), message.payload().userId(), message.payload().vid(),
                    message.payload().action(), e.getMessage(), e);
            throw new RuntimeException("互动事件消费处理失败: eventId=" + message.eventId(), e);
        } finally {
            MDC.remove("traceId");
        }
    }
}
