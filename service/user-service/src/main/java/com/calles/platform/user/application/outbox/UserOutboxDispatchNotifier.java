package com.calles.platform.user.application.outbox;

/**
 * 事务发件箱快速派发通知接口。
 */
public interface UserOutboxDispatchNotifier {

    /**
     * 在本地事务提交后通知异步派发指定事件。
     *
     * @param eventId 待派发事件全局唯一 ID
     */
    void notifyDispatch(String eventId);
}
