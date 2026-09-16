package com.calles.platform.audit.interfaces.http.controller;

import com.calles.platform.audit.application.coordinator.AuditTaskCoordinator;
import com.calles.platform.audit.application.service.AuditManualReviewApplicationService;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.interfaces.http.dto.AuditRequests;
import com.calles.platform.audit.interfaces.http.dto.AuditResponses;
import com.calles.platform.common.core.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核微服务内部端控制器 (InternalAuditController)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：微服务内部通信与内部调试专用接口端点，面向集群内部网络与集成测试；</li>
 *   <li><b>协作对象</b>：
 *     <ul>
 *       <li>{@link AuditTaskCoordinator}：模拟发起完整自动化机审流程；</li>
 *       <li>{@link AuditManualReviewApplicationService}：查询指定任务全景详情。</li>
 *     </ul>
 *   </li>
 *   <li><b>安全约束</b>：生产环境下该路径 `/api/audit/internal/**` 经由网关白名单拦截，仅限微服务间受信调用。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class InternalAuditController {

    /** 审核业务全流程协调器。 */
    private final AuditTaskCoordinator auditTaskCoordinator;

    /** 审核人工管理与工单应用服务。 */
    private final AuditManualReviewApplicationService manualReviewService;

    /**
     * 内部模拟提审触发自动化机审流水线（供研发调试与端到端链路自测使用）。
     *
     * @param request 包含视频 ID、公开短码、标题简介与媒体文件凭证的提审载荷
     * @return 执行后的审核任务概览响应
     */
    @PostMapping("/internal/submit")
    public ApiResponse<AuditResponses.TaskItem> mockSubmit(@Valid @RequestBody AuditRequests.MockSubmit request) {
        // 步骤 1：委托应用层协调器受理提审任务并执行全量机审流水线
        AuditTask task = auditTaskCoordinator.processVideoSubmission(
                request.videoId(),
                request.vid(),
                request.authorId(),
                request.title(),
                request.description(),
                request.coverFileId(),
                request.videoFileId()
        );

        // 步骤 2：防腐转换为对外安全传输 DTO
        return ApiResponse.ok(AuditResponses.TaskItem.fromDomain(task));
    }

    /**
     * 查询指定审核任务的判定结果与多维度机审证据明细。
     *
     * @param id 审核任务主键 ID (UUID 32位)
     * @return 审核任务全景视图响应
     */
    @GetMapping("/tasks/{id}")
    public ApiResponse<AuditResponses.TaskDetail> getTaskDetail(@PathVariable("id") String id) {
        // 步骤 1：委托应用服务查询任务与明细，不存在时由内部抛出 404 业务异常
        AuditResponses.TaskDetail detail = manualReviewService.getTaskDetail(id);

        // 步骤 2：返回统一 API 响应
        return ApiResponse.ok(detail);
    }
}
