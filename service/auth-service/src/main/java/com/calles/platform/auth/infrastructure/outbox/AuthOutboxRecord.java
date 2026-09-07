package com.calles.platform.auth.infrastructure.outbox;

import java.time.Instant;

/**
 * 待写入 auth_outbox 的不可变事件记录，属于认证服务内部持久化契约。
 *
 * @param eventId 稳定事件 UUID
 * @param aggregateId 认证账户 ID
 * @param eventType 事件类型
 * @param eventVersion 事件版本
 * @param payload 完整 JSON 信封
 * @param traceId 链路追踪 ID
 * @param occurredAt 事件发生时间
 */
public record AuthOutboxRecord(String eventId, String aggregateId, String eventType,
                               int eventVersion, String payload, String traceId,
                               Instant occurredAt) {
}
