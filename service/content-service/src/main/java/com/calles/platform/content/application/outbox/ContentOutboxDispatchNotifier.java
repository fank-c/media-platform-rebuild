package com.calles.platform.content.application.outbox;

/**
 * 事务性发件箱在事务提交后的快速投递通知器接口。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：应用层向基础设施层发出的出站快速投递意图契约；</li>
 *   <li><b>强一致保障</b>：严格要求在业务本地事务提交成功后 (afterCommit) 触发，防范脏读或未提交事件提前投递。</li>
 * </ul>
 * </p>
 */
public interface ContentOutboxDispatchNotifier {

    /**
     * 注册提交后快速投递通知。
     *
     * @param eventId 待快速投递的事件唯一全局 ID (UUID)
     */
    void notifyAfterCommit(String eventId);
}
