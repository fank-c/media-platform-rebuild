package com.calles.platform.user.infrastructure.outbox.notify;

import com.calles.platform.user.application.outbox.UserOutboxDispatchNotifier;
import com.calles.platform.user.infrastructure.outbox.dispatch.UserOutboxDispatcher;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后快速异步触发发件箱派发通知器。
 */
public class AfterCommitUserOutboxDispatchNotifier implements UserOutboxDispatchNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(AfterCommitUserOutboxDispatchNotifier.class);

    private final TaskExecutor taskExecutor;
    private final UserOutboxDispatcher dispatcher;

    public AfterCommitUserOutboxDispatchNotifier(TaskExecutor taskExecutor, UserOutboxDispatcher dispatcher) {
        this.taskExecutor = taskExecutor;
        this.dispatcher = dispatcher;
    }

    @Override
    public void notifyDispatch(String eventId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            LOGGER.debug("当前无活跃事务，直接异步提交派发: eventId={}", eventId);
            submitAsync(eventId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submitAsync(eventId);
            }
        });
    }

    private void submitAsync(String eventId) {
        try {
            taskExecutor.execute(() -> {
                try {
                    dispatcher.dispatchByEventId(eventId);
                } catch (Exception e) {
                    LOGGER.error("User Outbox 快速派发执行异常: eventId={}", eventId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            LOGGER.warn("User Outbox 快速派发线程池已满，将由后台扫描自愈兜底: eventId={}", eventId);
        } catch (Exception e) {
            LOGGER.error("User Outbox 快速派发任务提交异常: eventId={}", eventId, e);
        }
    }
}
