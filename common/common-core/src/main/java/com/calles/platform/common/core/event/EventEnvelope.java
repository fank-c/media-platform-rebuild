package com.calles.platform.common.core.event;

/**
 * 跨服务事件的通用传输信封，只表达版本化事件的公共元数据与载荷容器。
 *
 * <p>该类型不依赖 Spring、RabbitMQ、JSON 库或持久化框架；具体事件的字段校验、序列化策略和
 * 业务规则由各服务自行维护，避免 common-core 承载领域语义。</p>
 *
 * @param eventId 事件唯一标识，由生产方生成并供消费者幂等
 * @param eventType 事件语义名称，例如 {@code auth.account.created}
 * @param version 事件版本，由具体事件契约解释
 * @param occurredAt 事件发生时间的契约文本，格式由具体事件约束
 * @param producer 生产服务标识
 * @param aggregateId 事件关联聚合根标识
 * @param traceId 链路追踪标识，缺失或非法时由入站适配器安全兜底
 * @param payload 具体领域事件载荷
 * @param <T> 领域载荷类型
 */
public record EventEnvelope<T>(String eventId, String eventType, int version,
                               String occurredAt, String producer, String aggregateId,
                               String traceId, T payload) {
}
