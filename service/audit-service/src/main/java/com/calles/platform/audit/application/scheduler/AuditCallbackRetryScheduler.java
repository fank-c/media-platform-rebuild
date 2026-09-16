package com.calles.platform.audit.application.scheduler;

import com.calles.platform.audit.application.service.AuditCallbackService;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.CallbackStatus;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审核结果回调重试与自愈定时调度器 (AuditCallbackRetryScheduler)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务应用调度层，提供高可用补偿机制；</li>
 *   <li><b>自愈策略</b>：定时轮询因瞬时网络抖动导致回调失败但未达重试上限的任务，自动重新发起回调对齐状态；</li>
 *   <li><b>协作对象</b>：协同 {@link AuditTaskRepository} 扫描待重试任务，委托 {@link AuditCallbackService} 发起真实重试。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditCallbackRetryScheduler {

    /** 审核任务仓储。 */
    private final AuditTaskRepository auditTaskRepository;

    /** 审核结果回调服务。 */
    private final AuditCallbackService callbackService;

    /** 最大重试上限阈值，默认 5 次。 */
    @Value("${audit.callback.max-retries:5}")
    private int maxRetries;

    /**
     * 定时补偿扫描未成功回调的审核终结任务。
     *
     * <p><b>执行周期</b>：默认每 30 秒执行一轮补偿巡检（可由配置 {@code audit.callback.retry-interval-ms} 覆盖）。<br>
     * <b>批次保护</b>：单次最多拉取 20 条，避免重试流量瞬时风暴。</p>
     */
    @Scheduled(fixedDelayString = "${audit.callback.retry-interval-ms:30000}")
    public void retryPendingCallbacks() {
        // 步骤 1：扫描 callback_status=FAILED 且 retry_count < maxRetries 的未对齐终结工单
        List<AuditTask> failedTasks =
                auditTaskRepository.findPendingCallbacks(CallbackStatus.FAILED, maxRetries, 20);

        if (failedTasks.isEmpty()) {
            return;
        }

        log.info("检测到 [{}] 个待补偿重试回调的审核任务，开始执行自愈重试...", failedTasks.size());

        // 步骤 2：逐条发起补偿回调
        for (AuditTask task : failedTasks) {
            try {
                boolean success = callbackService.callbackContentService(task);
                if (success) {
                    // 步骤 2.1：自愈成功
                    log.info("任务 [{}] 补偿回调成功，已成功解除失败态", task.getTaskNo());
                } else {
                    // 步骤 2.2：依然失败，由 callbackService 自增 retryCount
                    log.warn("任务 [{}] 补偿回调仍失败，当前已重试 [{}] 次", task.getTaskNo(), task.getCallbackRetries());
                }
            } catch (Exception e) {
                // 步骤 2.3：隔离单个任务异常，保障批量巡检不中断
                log.error("补偿任务 [{}] 执行发生未捕获异常: {}", task.getTaskNo(), e.getMessage());
            }
        }
    }
}
