package com.calles.platform.auth.infrastructure.outbox;

import com.calles.platform.auth.application.outbox.AuthOutboxDispatchNotifier;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 基于事务提交回调的快速提示适配器。
 *
 * <p>回调只把 eventId 交给有界执行器；数据库领取、RabbitMQ 发送和状态回写均在工作线程中通过
 * 独立事务执行。它不是可靠投递通道，任何拒绝或任务异常都会回退到扫描恢复。</p>
 */
public class AfterCommitAuthOutboxDispatchNotifier implements AuthOutboxDispatchNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(AfterCommitAuthOutboxDispatchNotifier.class);

    /** 执行快速任务的有限线程池，拒绝时绝不回到注册线程同步执行。 */
    private final TaskExecutor taskExecutor;
    /** 按 ID 领取并发送的统一分发器。 */
    private final AuthOutboxDispatcher dispatcher;
    /** 仅记录低基数提示结果，不影响事务回调。 */
    private final AuthOperationalMetrics metrics;

    /**
     * 创建提交后快速提示适配器。
     *
     * @param taskExecutor 有界快速执行器
     * @param dispatcher 统一按 ID 分发器
     * @param metrics Outbox 指标出口
     */
    public AfterCommitAuthOutboxDispatchNotifier(TaskExecutor taskExecutor, AuthOutboxDispatcher dispatcher,
            AuthOperationalMetrics metrics) {
        this.taskExecutor = taskExecutor;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
    }

    /**
     * 仅在当前存在真实同步事务时登记 afterCommit 回调。
     *
     * <p>若调用路径未经过 Spring 事务代理，不同步补发，避免把“事务内通知”的错误接线伪装为
     * 正确行为；持久化记录仍会由扫描任务恢复。</p>
     *
     * @param eventId 已落库事件 ID
     */
    @Override
    public void notifyAfterCommit(String eventId) {
        // 注册成功与事务提交必须严格分离，提交前不允许任务领取或发送消息。
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            metrics.recordFastDispatchHint("no_transaction");
            LOGGER.warn("Outbox 快速提示未登记事务回调，eventId={}", eventId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(eventId);
            }
        });
    }

    /**
     * 在提交成功后非阻塞地提交 ID 任务；拒绝不能传播回已完成的注册用例。
     *
     * @param eventId 已提交事件 ID
     */
    private void submit(String eventId) {
        try {
            taskExecutor.execute(() -> runDispatch(eventId));
            metrics.recordFastDispatchHint("accepted");
        } catch (RejectedExecutionException exception) {
            metrics.recordFastDispatchHint("rejected");
            LOGGER.warn("Outbox 快速提示被有界执行器拒绝，eventId={}", eventId);
        } catch (RuntimeException exception) {
            // 回调异常不能反向影响提交完成后的业务调用方，扫描会处理已提交记录。
            metrics.recordFastDispatchHint("submission_failed");
            LOGGER.warn("Outbox 快速提示提交失败，eventId={}，reason={}", eventId,
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * 收敛工作线程异常，保证后续队列任务仍可继续执行。
     *
     * @param eventId 需要尝试分发的事件 ID
     */
    private void runDispatch(String eventId) {
        try {
            dispatcher.dispatchByEventId(eventId);
        } catch (RuntimeException exception) {
            metrics.recordFastDispatchHint("task_failed");
            LOGGER.warn("Outbox 快速任务异常结束，eventId={}，reason={}", eventId,
                    exception.getClass().getSimpleName());
        }
    }
}
