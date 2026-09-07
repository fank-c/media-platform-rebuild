package com.calles.platform.auth.infrastructure.outbox;

import com.calles.platform.auth.application.outbox.AuthOutboxDispatchNotifier;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;

/**
 * 快速投递关闭时使用的空提示实现。
 *
 * <p>它不阻断注册事务，也不尝试同步发送；事件继续由持久化 Outbox 扫描在总发送开关开启时恢复。</p>
 */
public class NoopAuthOutboxDispatchNotifier implements AuthOutboxDispatchNotifier {

    /** 记录快速通道未启用，便于区分未投递与提示被拒绝。 */
    private final AuthOperationalMetrics metrics;

    /**
     * @param metrics Outbox 指标出口
     */
    public NoopAuthOutboxDispatchNotifier(AuthOperationalMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 不登记任务，只保留已落库事件等待扫描。
     *
     * @param eventId 已落库事件 ID；不写入指标标签
     */
    @Override
    public void notifyAfterCommit(String eventId) {
        metrics.recordFastDispatchHint("disabled");
    }
}
