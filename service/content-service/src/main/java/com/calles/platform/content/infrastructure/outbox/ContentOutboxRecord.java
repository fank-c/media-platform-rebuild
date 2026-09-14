package com.calles.platform.content.infrastructure.outbox;

import java.time.Instant;

/**
 * 待写入 content_outbox 的不可变事件记录。
 *
 * @param eventId 稳定事件 UUID
 * @param aggregateId 视频聚合根 ID
 * @param eventType 领域事件类型 (如 content.video.published)
 * @param eventVersion 事件版本
 * @param payload 事件载荷 JSON
 * @param traceId 链路追踪 ID
 * @param occurredAt 事件发生时间
 */
public record ContentOutboxRecord(String eventId, String aggregateId, String eventType,
                                  int eventVersion, String payload, String traceId,
                                  Instant occurredAt) {
}
