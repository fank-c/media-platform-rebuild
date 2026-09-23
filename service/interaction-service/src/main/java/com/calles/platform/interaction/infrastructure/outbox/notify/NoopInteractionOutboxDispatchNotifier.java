package com.calles.platform.interaction.infrastructure.outbox.notify;

import com.calles.platform.interaction.application.outbox.InteractionOutboxDispatchNotifier;

/**
 * 快速派发通知器的空实现（当 fastDispatchEnabled 为 false 或未装配线程池时作为降级备选）。
 */
public class NoopInteractionOutboxDispatchNotifier implements InteractionOutboxDispatchNotifier {

    @Override
    public void notifyDispatch(String eventId) {
        // 空操作：仅持久化至 Outbox 表，等待后台调度扫描或保持待派发状态
    }
}
