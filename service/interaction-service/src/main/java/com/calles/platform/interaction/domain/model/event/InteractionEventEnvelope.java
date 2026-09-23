package com.calles.platform.interaction.domain.model.event;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 统一交互行为事件传输信封，契约与全站推荐及下游消费规范 100% 对齐。
 *
 * @param eventId 事件全局唯一标识 (32位无中划线 UUID)
 * @param eventType 事件类型标识 (固定为 interaction.video-action)
 * @param eventVersion 事件契约版本号 (当前固定为 1)
 * @param traceId 全链路追踪上下文 ID
 * @param occurredAt 事件发生时间 (ISO-8601 UTC 格式字符串)
 * @param payload 具体业务交互载荷
 * @param <T> 载荷类型
 */
public record InteractionEventEnvelope<T>(
        String eventId,
        String eventType,
        @JsonProperty("eventVersion") int eventVersion,
        String traceId,
        String occurredAt,
        T payload
) {
}
