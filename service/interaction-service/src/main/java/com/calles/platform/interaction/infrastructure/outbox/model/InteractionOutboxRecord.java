package com.calles.platform.interaction.infrastructure.outbox.model;

import java.time.Instant;

/**
 * 待写入 interaction_outbox 发件箱数据表的不可变领域事件记录对象。
 *
 * @param eventId 稳定事件 UUID，重试和重放必须复用
 * @param aggregateId 业务聚合根 ID (如 vid 或 userId:vid)
 * @param eventType 事件类型标识 (固定为 interaction.video-action)
 * @param eventVersion 契约版本号 (固定为 1)
 * @param payload 符合推荐交互流模板的事件 JSON 载荷
 * @param traceId 链路追踪上下文 ID
 * @param occurredAt 事件发生时间
 */
public record InteractionOutboxRecord(
        String eventId,
        String aggregateId,
        String eventType,
        int eventVersion,
        String payload,
        String traceId,
        Instant occurredAt
) {
    /**
     * 工厂方法构造 InteractionOutboxRecord。
     *
     * @param eventId 事件全局唯一 ID
     * @param aggregateId 关联聚合根 ID
     * @param eventType 事件类型标识
     * @param payload JSON 载荷
     * @param traceId 链路追踪 ID
     * @param occurredAt 发生时间
     * @return 完备的 InteractionOutboxRecord
     */
    public static InteractionOutboxRecord of(String eventId,
                                             String aggregateId,
                                             String eventType,
                                             String payload,
                                             String traceId,
                                             Instant occurredAt) {
        return new InteractionOutboxRecord(eventId, aggregateId, eventType, 1, payload, traceId, occurredAt);
    }
}
