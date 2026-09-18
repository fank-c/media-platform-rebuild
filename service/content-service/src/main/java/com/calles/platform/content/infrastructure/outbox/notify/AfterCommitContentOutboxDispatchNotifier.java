package com.calles.platform.content.infrastructure.outbox.notify;

import com.calles.platform.content.application.outbox.ContentOutboxDispatchNotifier;
import com.calles.platform.content.infrastructure.outbox.dispatch.ContentOutboxDispatcher;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 基于 Spring 事务同步管理器 (TransactionSynchronizationManager) 的提交后快速投递通知器。
 *
 * <p>职责与机制说明：
 * <ul>
 *   <li><b>严格时序</b>：通过 {@link TransactionSynchronization#afterCommit()} 挂载回调，保证只有数据库事务切实 COMMIT 成功后才唤醒发送；</li>
 *   <li><b>虚拟线程异步派发</b>：将分发任务交由虚拟线程执行器异步运行，绝不阻塞当前业务工作流事务完成；</li>
 *   <li><b>降级容灾</b>：若线程池饱和或提交失败，静默回退由后台定时扫描任务 (ScanJob) 兜底补偿，不反向影响业务成功响应。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class AfterCommitContentOutboxDispatchNotifier implements ContentOutboxDispatchNotifier {

    /** 快速分发异步任务执行器。 */
    private final TaskExecutor taskExecutor;

    /** 统一任务分发中枢。 */
    private final ContentOutboxDispatcher dispatcher;

    public AfterCommitContentOutboxDispatchNotifier(TaskExecutor taskExecutor, ContentOutboxDispatcher dispatcher) {
        this.taskExecutor = taskExecutor;
        this.dispatcher = dispatcher;
    }

    /**
     * 在当前事务的 afterCommit 钩子中挂载快速分发回调。
     *
     * @param eventId 待快速投递的事件唯一 ID
     */
    @Override
    public void notifyAfterCommit(String eventId) {
        // 步骤 1：事务活跃性校验
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("当前环境无活跃事务同步，跳过注册 afterCommit 回调: eventId={}", eventId);
            return;
        }

        // 步骤 2：向当前事务注册 afterCommit 回调
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submitAsync(eventId);
            }
        });
    }

    /**
     * 异步提交事件 ID 派发任务至线程池。
     *
     * @param eventId 事件 ID
     */
    private void submitAsync(String eventId) {
        try {
            taskExecutor.execute(() -> {
                try {
                    dispatcher.dispatchByEventId(eventId);
                } catch (Exception e) {
                    log.error("Outbox 快速投递异步分发异常: eventId={}", eventId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Outbox 快速投递线程池已满，将由后台扫描任务自愈补偿: eventId={}", eventId);
        } catch (Exception e) {
            log.error("Outbox 快速投递任务提交异常: eventId={}", eventId, e);
        }
    }
}
