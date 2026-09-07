package com.calles.platform.auth.infrastructure.outbox;

import static org.mockito.Mockito.verify;

import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 提交后快速提示适配器的事务边界测试。 */
@ExtendWith(MockitoExtension.class)
class AfterCommitAuthOutboxDispatchNotifierTest {

    @Mock private AuthOutboxDispatcher dispatcher;
    @Mock private AuthOperationalMetrics metrics;

    /** 清理线程本地事务状态，避免测试间相互污染。 */
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /** 回调登记前不领取、不发送；只有模拟成功提交后才把 ID 交给工作执行器。 */
    @Test
    void submitsOnlyAfterCommit() {
        AfterCommitAuthOutboxDispatchNotifier notifier = new AfterCommitAuthOutboxDispatchNotifier(
                Runnable::run, dispatcher, metrics);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        notifier.notifyAfterCommit("event-1");

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(dispatcher).dispatchByEventId("event-1");
        verify(metrics).recordFastDispatchHint("accepted");
    }

    /** 缺失真实事务代理时不补发，避免把错误接线伪装成可靠行为。 */
    @Test
    void doesNotSubmitWithoutTransaction() {
        AfterCommitAuthOutboxDispatchNotifier notifier = new AfterCommitAuthOutboxDispatchNotifier(
                Runnable::run, dispatcher, metrics);

        notifier.notifyAfterCommit("event-1");

        verify(metrics).recordFastDispatchHint("no_transaction");
    }
}
