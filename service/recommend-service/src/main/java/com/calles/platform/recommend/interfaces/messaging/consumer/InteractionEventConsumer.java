package com.calles.platform.recommend.interfaces.messaging.consumer;

import com.calles.platform.recommend.config.RecommendMessagingConfiguration;
import com.calles.platform.recommend.interfaces.messaging.dispatcher.InteractionEventDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 推荐微服务统一交互事件 (视频互动与作者关注) RabbitMQ 消费者 (InteractionEventConsumer)。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>监听队列</b>：{@link RecommendMessagingConfiguration#INTERACTION_ACTION_QUEUE}；</li>
 *   <li><b>统一信封反序列化与安全校验</b>：防御空值、畸形格式、版本不兼容与关键元数据残缺；</li>
 *   <li><b>全链路追踪</b>：提取 {@code traceId} 绑定至 MDC，确保日志链路贯通，并在 finally 中严格清理；</li>
 *   <li><b>业务分发</b>：委托 {@link InteractionEventDispatcher} 依据 {@code eventType} 精确分发；</li>
 *   <li><b>毒丸防御与异常重试</b>：格式错误与未知事件安全丢弃，应用系统瞬态异常向上抛出触发 MQ 重试。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionEventConsumer {

    private final InteractionEventDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    /**
     * 监听并消费统一交互行为领域事件。
     *
     * @param payload 原始 JSON 报文字符串
     */
    @RabbitListener(queues = RecommendMessagingConfiguration.INTERACTION_ACTION_QUEUE)
    public void onInteractionEvent(String payload) {
        log.info("推荐服务接收交互行为事件: payloadLength={}", payload != null ? payload.length() : 0);

        if (payload == null || payload.isBlank()) {
            log.warn("丢弃空白交互事件报文");
            return;
        }

        // 步骤 1：初步解析为 JsonNode 树，畸形 JSON 属于不可恢复格式错误，直接丢弃
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("反序列化交互事件 JSON 失败，丢弃非法消息: payload={}, error={}", payload, e.getMessage(), e);
            return;
        }

        if (rootNode == null || !rootNode.isObject()) {
            log.warn("丢弃非 JSON Object 格式的交互事件: payload={}", payload);
            return;
        }

        // 步骤 2：核心信封公共字段防御校验
        JsonNode eventIdNode = rootNode.get("eventId");
        JsonNode eventTypeNode = rootNode.get("eventType");
        JsonNode payloadNode = rootNode.get("payload");

        if (eventIdNode == null || !eventIdNode.isTextual() || eventIdNode.asText().isBlank()
                || eventTypeNode == null || !eventTypeNode.isTextual() || eventTypeNode.asText().isBlank()
                || payloadNode == null || payloadNode.isNull() || !payloadNode.isObject()) {
            log.warn("丢弃缺失关键信封字段 (eventId/eventType/payload) 的交互事件: payload={}", payload);
            return;
        }

        String eventId = eventIdNode.asText().trim();
        String eventType = eventTypeNode.asText().trim();

        // 校验版本号 (当前协议版本为 1)
        JsonNode versionNode = rootNode.get("eventVersion");
        if (versionNode == null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt()
                || versionNode.intValue() != 1) {
            log.warn("丢弃缺失或不受支持的契约版本交互事件: eventId={}", eventId);
            return;
        }

        // 步骤 3：提取并绑定全链路日志追踪 ID
        JsonNode traceIdNode = rootNode.get("traceId");
        String traceId = (traceIdNode != null && !traceIdNode.asText().isBlank())
                ? traceIdNode.asText().trim()
                : eventId;

        try {
            MDC.put("traceId", traceId);

            // 步骤 4：委托分发器进行业务分发
            dispatcher.dispatch(eventType, payload);
        } catch (Exception e) {
            log.error("处理交互事件发生未捕获异常，向上抛出触发 MQ 重试: eventId={}, eventType={}, error={}",
                    eventId, eventType, e.getMessage(), e);
            throw new RuntimeException("交互事件消费处理失败: eventId=" + eventId, e);
        } finally {
            MDC.remove("traceId");
        }
    }
}
