package com.calles.platform.audit.application.service;

import com.calles.platform.audit.application.client.ContentServiceClient;
import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import com.calles.platform.common.core.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 审核结果下游回调通知服务 (AuditCallbackService)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：应用服务层负责与下游 `content-service` 进行内部微服务状态对齐的通信客户端封装；</li>
 *   <li><b>协作对象</b>：委托 {@link ContentServiceClient} 发送 HTTP 内部专用回调，联动更新 {@link AuditTaskRepository} 回调状态；</li>
 *   <li><b>高可用韧性</b>：捕获各类瞬时网络异常并打标为 FAILED，交由 {@link com.calles.platform.audit.application.scheduler.AuditCallbackRetryScheduler} 兜底重试。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditCallbackService {

    /** 内容微服务 Feign 客户端。 */
    private final ContentServiceClient contentServiceClient;

    /** 审核任务仓储。 */
    private final AuditTaskRepository auditTaskRepository;

    /**
     * 触发向内容微服务回调审核终局裁决结论。
     *
     * <p><b>前置约束</b>：仅当任务已产生明确的终局结论 (PASSED 或 REJECTED) 时才允许发起回调。<br>
     * <b>副作用</b>：更新任务聚合根的 callbackStatus 与 callbackRetries，并持久化回写数据库。</p>
     *
     * @param task 已完结终审判定的审核任务聚合根实体
     * @return true 若回调内容服务成功并收到 200 响应，false 若失败或产生网络异常
     */
    public boolean callbackContentService(AuditTask task) {
        // 步骤 1：前置状态有效性校验：拒绝未终审判定的任务触发回调
        if (task == null || task.getResult() == AuditResult.PENDING) {
            log.warn("审核任务尚未产生最终判定，拒绝执行回调");
            return false;
        }

        // 步骤 2：提取结论是否通过与驳回原因
        boolean passed = (task.getResult() == AuditResult.PASSED);
        String reason = task.getRejectReason();

        // 步骤 3：构造 Feign 回调载荷对象
        ContentServiceClient.AuditCallbackPayload payload =
                new ContentServiceClient.AuditCallbackPayload(task.getBizId(), passed, reason);

        try {
            log.info("向内容微服务发起审核判定回调: videoId=[{}], passed=[{}], reason=[{}]",
                    task.getBizId(), passed, reason);

            // 步骤 4：通过 OpenFeign 发起同步 RPC 调用
            ApiResponse<Void> response = contentServiceClient.notifyAuditResult(payload);

            // 步骤 5：核验响应结果并更新状态
            if (response != null && response.code() == 200) {
                // 步骤 5.1：成功分支：标记 callbackStatus=SUCCESS
                task.markCallbackSuccess();
                auditTaskRepository.updateById(task);
                log.info("审核回调成功通知内容微服务，videoId=[{}]", task.getBizId());
                return true;
            } else {
                // 步骤 5.2：业务错误响应分支：标记 callbackStatus=FAILED
                log.error("内容微服务响应回调失败，响应: [{}]", response);
                task.markCallbackFailed();
                auditTaskRepository.updateById(task);
                return false;
            }
        } catch (Exception e) {
            // 步骤 6：网络异常与超时兜底捕获：标记 FAILED，等待定时调度器自愈重试
            log.error("远程调用内容微服务回调发生网络抖动或异常，videoId=[{}], 错误: {}",
                    task.getBizId(), e.getMessage());
            task.markCallbackFailed();
            auditTaskRepository.updateById(task);
            return false;
        }
    }
}
