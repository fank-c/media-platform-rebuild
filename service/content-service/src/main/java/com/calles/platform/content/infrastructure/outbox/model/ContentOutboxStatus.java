package com.calles.platform.content.infrastructure.outbox.model;

/**
 * 内容领域事件事务性发件箱 (Outbox) 状态机枚举。
 *
 * <p>职责与持久化约束：
 * <ul>
 *   <li><b>所属边界</b>：严格约束 {@code content_outbox.status} 数据库列枚举值；</li>
 *   <li><b>状态流转路径</b>：
 *     PENDING（待发布） -&gt; PROCESSING（已被某实例原子锁定抢占） -&gt; PUBLISHED（Broker 确认成功）或 FAILED（重试耗尽）。
 *   </li>
 * </ul>
 * </p>
 */
public enum ContentOutboxStatus {

    /** 初始待处理或退避到期等待再次投递状态。 */
    PENDING("PENDING"),

    /** 已被特定发布实例通过 CAS 原子认领，处于租约保护期中。 */
    PROCESSING("PROCESSING"),

    /** 已成功投递至 RabbitMQ 并收到 Broker Confirm ACK 确认。 */
    PUBLISHED("PUBLISHED"),

    /** 超过最大允许重试次数上限，流转至终态待告警与人工处置。 */
    FAILED("FAILED");

    /** 数据库表中对应的持久化字符串值。 */
    private final String databaseValue;

    ContentOutboxStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    /**
     * 获取数据库字段对应的字面量值。
     *
     * @return 数据库字段枚举字面量
     */
    public String databaseValue() {
        return databaseValue;
    }
}
