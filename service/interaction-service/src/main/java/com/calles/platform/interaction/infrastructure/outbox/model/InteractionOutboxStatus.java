package com.calles.platform.interaction.infrastructure.outbox.model;

/**
 * 互动服务 Outbox 发件箱记录生命周期状态枚举。
 */
public enum InteractionOutboxStatus {

    /** 待派发：已在本地业务事务中持久化，等待快速通知或定时自愈扫描提取。 */
    PENDING("PENDING"),

    /** 处理中：已被某一应用实例 CAS 抢占成功并持有排他租约，正执行 RabbitMQ 网络发送与 Confirm 确认。 */
    PROCESSING("PROCESSING"),

    /** 已发布：已获得 Broker 确认 ACK 且无 Return 回退，本条消息生命周期安全终结。 */
    PUBLISHED("PUBLISHED"),

    /** 失败终态：重试次数超过最大上限仍未成功，收敛至此状态供监控告警与人工干预。 */
    FAILED("FAILED");

    private final String databaseValue;

    InteractionOutboxStatus(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}
