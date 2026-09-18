package com.calles.platform.content.infrastructure.outbox.model;

/**
 * 已被当前发布实例通过 CAS 条件原子认领的不可变 Outbox 消息快照。
 *
 * <p>职责说明：
 * <ul>
 *   <li><b>所属边界</b>：发件箱投递链路内部不可变数据传输对象；</li>
 *   <li><b>防冲突保护</b>：包含 {@code claimToken}，用于在投递确认或失败回写时防范租约过期后覆盖其他并发实例的最新状态；</li>
 *   <li><b>跨服务路由</b>：携带 {@code eventType}，作为 RabbitMQ 发送时的精确 Topic Routing Key。</li>
 * </ul>
 * </p>
 *
 * @param eventId 领域事件全局唯一主键 ID (UUID)
 * @param eventType 领域事件类型名称 (如 content.video.submitted, content.video.published)
 * @param payload 事件载荷 JSON 字符串
 * @param traceId 分布式链路追踪 Trace ID (可为空)
 * @param claimToken 本次成功认领生成的分布式租约唯一令牌 (UUID)
 * @param attempts 当前消息已被尝试投递的累计次数
 */
public record ClaimedOutboxMessage(
        String eventId,
        String eventType,
        String payload,
        String traceId,
        String claimToken,
        int attempts
) {
}
