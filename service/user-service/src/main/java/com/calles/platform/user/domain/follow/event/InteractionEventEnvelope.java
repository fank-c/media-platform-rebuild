package com.calles.platform.user.domain.follow.event;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 统一交互行为事件传输信封，契约与推荐流互动模板 100% 对齐。
 *
 * @param eventId 事件唯一标识 (UUID)
 * @param eventType 事件类型标识 (如 interaction.author-action, interaction.video-action)
 * @param eventVersion 事件契约版本号 (固定为 1)
 * @param traceId 全链路追踪 ID
 * @param occurredAt 事件发生时间 (ISO-8601 UTC 字符串)
 * @param payload 具体交互载荷
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
