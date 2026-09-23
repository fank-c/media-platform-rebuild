package com.calles.platform.user.infrastructure.outbox.notify;

import com.calles.platform.user.infrastructure.outbox.dispatch.UserOutboxDispatcher;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("AfterCommitUserOutboxDispatchNotifier 提交后通知器测试")
class AfterCommitUserOutboxDispatchNotifierTest {

    @Mock
    private UserOutboxDispatcher dispatcher;

    @Mock
    private TaskExecutor rejectingExecutor;

    private AfterCommitUserOutboxDispatchNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new AfterCommitUserOutboxDispatchNotifier(new SyncTaskExecutor(), dispatcher);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("无活跃事务时直接异步派发")
    void nonTransactionalContextDirectlySubmits() {
        notifier.notifyDispatch("event-1");

        verify(dispatcher).dispatchByEventId("event-1");
    }

    @Test
    @DisplayName("事务提交成功后触发快速分发")
    void activeTransactionTriggersOnAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        notifier.notifyDispatch("event-1");

        verify(dispatcher, never()).dispatchByEventId("event-1");

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        verify(dispatcher).dispatchByEventId("event-1");
    }

    @Test
    @DisplayName("线程池饱和拒绝时安全捕获异常且不阻断业务")
    void executorRejectionHandledGracefully() {
        doThrow(new RejectedExecutionException("Full")).when(rejectingExecutor).execute(any());
        AfterCommitUserOutboxDispatchNotifier rejectingNotifier =
                new AfterCommitUserOutboxDispatchNotifier(rejectingExecutor, dispatcher);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        rejectingNotifier.notifyDispatch("event-1");

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        verify(dispatcher, never()).dispatchByEventId("event-1");
    }
}
