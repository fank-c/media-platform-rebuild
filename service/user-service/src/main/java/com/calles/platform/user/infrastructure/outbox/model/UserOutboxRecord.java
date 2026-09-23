package com.calles.platform.user.infrastructure.outbox.model;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * 待写入 user_outbox 发件箱数据表的不可变领域事件记录对象。
 *
 * @param eventId 领域事件全局唯一主键 ID (UUID 32位无短横线)
 * @param aggregateId 用户聚合根主键 ID (发起用户 userId)
 * @param eventType 领域事件类型名称 (如 "interaction.author-action")
 * @param eventVersion 事件契约版本号 (当前统一为 1)
 * @param payload 事件载荷 JSON 字符串 (符合统一推荐交互模板)
 * @param traceId 链路追踪 Trace ID
 * @param occurredAt 事件发生的实际时间戳
 */
public record UserOutboxRecord(
        String eventId,
        String aggregateId,
        String eventType,
        int eventVersion,
        String payload,
        String traceId,
        Instant occurredAt
) {

    /**
     * 便捷静态工厂方法：自动生成 32 位 UUID eventId，并优先从 SLF4J MDC 提取 traceId。
     *
     * @param aggregateId 聚合根 ID
     * @param eventType 领域事件类型
     * @param payload 事件载荷 JSON
     * @param occurredAt 发生时间戳
     * @return 完备的 UserOutboxRecord
     */
    public static UserOutboxRecord of(
            String aggregateId,
            String eventType,
            String payload,
            Instant occurredAt
    ) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = eventId;
        }
        return new UserOutboxRecord(eventId, aggregateId, eventType, 1, payload, traceId, occurredAt);
    }
}
