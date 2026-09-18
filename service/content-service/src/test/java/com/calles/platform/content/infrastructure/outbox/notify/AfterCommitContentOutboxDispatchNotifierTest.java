package com.calles.platform.content.infrastructure.outbox.notify;

import com.calles.platform.content.infrastructure.outbox.dispatch.ContentOutboxDispatcher;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AfterCommitContentOutboxDispatchNotifier 提交后快速通知器单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AfterCommitContentOutboxDispatchNotifier 提交后通知器测试")
class AfterCommitContentOutboxDispatchNotifierTest {

    @Mock
    private ContentOutboxDispatcher dispatcher;

    @Mock
    private TaskExecutor rejectingExecutor;

    private AfterCommitContentOutboxDispatchNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new AfterCommitContentOutboxDispatchNotifier(new SyncTaskExecutor(), dispatcher);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("无活跃事务时不注册回调且不派发")
    void nonTransactionalContextDoesNotTrigger() {
        notifier.notifyAfterCommit("event-1");

        verify(dispatcher, never()).dispatchByEventId("event-1");
    }

    @Test
    @DisplayName("事务提交成功后触发异步快速分发")
    void activeTransactionTriggersOnAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        notifier.notifyAfterCommit("event-1");

        // 模拟 Spring 事务提交完成钩子
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        verify(dispatcher).dispatchByEventId("event-1");
    }

    @Test
    @DisplayName("线程池饱和拒绝时安全捕获异常且不阻断业务")
    void executorRejectionHandledGracefully() {
        doThrow(new RejectedExecutionException("Full")).when(rejectingExecutor).execute(any());
        AfterCommitContentOutboxDispatchNotifier rejectingNotifier =
                new AfterCommitContentOutboxDispatchNotifier(rejectingExecutor, dispatcher);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        rejectingNotifier.notifyAfterCommit("event-1");

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        verify(dispatcher, never()).dispatchByEventId("event-1");
    }
}
