package com.calles.platform.auth.application.outbox;

/**
 * 认证用例向 Outbox 快速投递通道发送提交后唤醒提示的窄接口。
 *
 * <p>该接口只接收已持久化事件的 ID，不负责读取事件、领取租约、发送 RabbitMQ 消息或改变注册
 * 事务结果。快速提示可因队列拒绝、停机而丢失，可靠恢复始终由持久化 Outbox 扫描承担。</p>
 */
public interface AuthOutboxDispatchNotifier {

    /**
     * 在注册事务内登记“仅成功提交后”执行的快速投递提示。
     *
     * @param eventId 已写入 auth_outbox 的稳定事件 ID
     */
    void notifyAfterCommit(String eventId);
}
