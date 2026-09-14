package com.calles.platform.content.infrastructure.outbox;

import java.time.Instant;

/**
 * 待写入 content_outbox 发件箱数据表的不可变领域事件记录对象。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：发件箱基础设施层的核心传输数据结构；</li>
 *   <li><b>协作对象</b>：由应用服务组装，传递给 {@link ContentOutboxMapper} 进行持久化写入；</li>
 *   <li><b>幂等与溯源</b>：携带稳定的全局唯一 {@code eventId} (UUID) 及分布式链路追踪标识 {@code traceId}。</li>
 * </ul>
 * </p>
 *
 * @param eventId 领域事件全局唯一主键 ID (UUID 32位无短横线，用于消费端幂等去重)
 * @param aggregateId 视频聚合根主键 ID (关联 video_content.id)
 * @param eventType 领域事件类型名称 (如 "content.video.submitted", "content.video.published")
 * @param eventVersion 事件契约版本号 (当前统一为 1)
 * @param payload 事件载荷 JSON 字符串 (符合领域契约 schema)
 * @param traceId 当前调用链链路追踪 Trace ID (可为空)
 * @param occurredAt 业务事实在领域聚合中发生的实际时间戳
 */
public record ContentOutboxRecord(
        String eventId,
        String aggregateId,
        String eventType,
        int eventVersion,
        String payload,
        String traceId,
        Instant occurredAt
) {
}
