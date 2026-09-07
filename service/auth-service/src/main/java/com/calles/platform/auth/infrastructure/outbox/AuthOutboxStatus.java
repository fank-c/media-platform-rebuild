package com.calles.platform.auth.infrastructure.outbox;

/**
 * 认证领域事件 Outbox 的投递状态。
 *
 * <p>该枚举仅约束 auth-service 自有表 {@code auth_outbox.status} 的持久化值，不作为跨服务事件字段，
 * 也不表达用户资料或认证账户的业务状态。</p>
 */
public enum AuthOutboxStatus {

    /** 事件等待首次发送或等待下一次重试。 */
    PENDING("PENDING"),
    /** 事件已被某个发布实例领取，租约到期前不应被其他实例重复处理。 */
    PROCESSING("PROCESSING"),
    /** RabbitMQ 已确认接收且消息未被退回，记录不再自动发送。 */
    PUBLISHED("PUBLISHED"),
    /** 自动重试次数耗尽，保留记录等待告警后的受控重放。 */
    FAILED("FAILED");

    /** 与数据库检查约束一致的稳定持久化值，禁止以枚举名称重构替代历史数据迁移。 */
    private final String databaseValue;

    /**
     * 创建一个带稳定持久化值的 Outbox 状态。
     *
     * @param databaseValue 写入 {@code auth_outbox.status} 的值
     */
    AuthOutboxStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    /**
     * 获取供 JDBC SQL 参数使用的数据库值。
     *
     * @return 与表检查约束一致的状态字符串
     */
    public String databaseValue() {
        return databaseValue;
    }
}
