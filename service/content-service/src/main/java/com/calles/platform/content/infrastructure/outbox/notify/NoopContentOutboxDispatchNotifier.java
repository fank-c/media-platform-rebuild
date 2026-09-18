package com.calles.platform.content.infrastructure.outbox.notify;

import com.calles.platform.content.application.outbox.ContentOutboxDispatchNotifier;
import lombok.extern.slf4j.Slf4j;

/**
 * 快速投递未开启时的降级空通知器实现。
 *
 * <p>职责说明：当快速投递开关关闭时作为缺省 Bean 注入，不执行任何异步提交，所有事件由后台定时补偿扫描任务接管。</p>
 */
@Slf4j
public class NoopContentOutboxDispatchNotifier implements ContentOutboxDispatchNotifier {

    @Override
    public void notifyAfterCommit(String eventId) {
        log.debug("Outbox 快速投递功能未开启，事件将由定时自愈扫描调度投递: eventId={}", eventId);
    }
}
