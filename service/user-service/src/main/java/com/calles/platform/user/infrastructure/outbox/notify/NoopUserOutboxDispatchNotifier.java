package com.calles.platform.user.infrastructure.outbox.notify;

import com.calles.platform.user.application.outbox.UserOutboxDispatchNotifier;

/**
 * 空操作通知器，用于禁用快速分发或单测隔离。
 */
public class NoopUserOutboxDispatchNotifier implements UserOutboxDispatchNotifier {

    @Override
    public void notifyDispatch(String eventId) {
        // 空操作
    }
}
