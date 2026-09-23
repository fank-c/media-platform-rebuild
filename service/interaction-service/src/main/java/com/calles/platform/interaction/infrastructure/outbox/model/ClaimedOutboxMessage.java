package com.calles.platform.interaction.infrastructure.outbox.model;

import java.time.Instant;

/**
 * 已被当前实例原子认领成功并持有有效租约的 Outbox 发送任务快照。
 *
 * @param eventId 事件稳定 UUID
 * @param aggregateId 关联聚合根 ID
 * @param eventType 事件类型
 * @param eventVersion 契约版本号
 * @param payload 事件 JSON 载荷
 * @param traceId 链路追踪 ID
 * @param occurredAt 事件发生时间
 * @param attempts 已执行投递尝试次数
 * @param claimToken 本次认领分配的排他安全令牌
 */
public record ClaimedOutboxMessage(
        String eventId,
        String aggregateId,
        String eventType,
        int eventVersion,
        String payload,
        String traceId,
        Instant occurredAt,
        int attempts,
        String claimToken
) {
}
