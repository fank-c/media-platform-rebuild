package com.calles.platform.user.infrastructure.outbox.model;

import java.time.Instant;

/**
 * 当前实例通过 CAS 原子操作认领成功的 Outbox 消息不可变快照。
 *
 * @param eventId 领域事件全局唯一主键 ID (UUID)
 * @param aggregateId 关联聚合根主键 ID (用户账号ID)
 * @param eventType 领域事件类型名称
 * @param eventVersion 事件契约版本号
 * @param payload 事件载荷 JSON 字符串
 * @param traceId 链路追踪 ID
 * @param occurredAt 事件发生时间戳
 * @param attempts 已发生并包含本次的尝试递增计数
 * @param claimToken 本次成功抢占租约的独占令牌，用于后续排他结果回写
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
