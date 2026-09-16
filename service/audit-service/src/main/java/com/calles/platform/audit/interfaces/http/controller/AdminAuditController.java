package com.calles.platform.audit.interfaces.http.controller;

import com.calles.platform.audit.application.service.AuditManualReviewApplicationService;
import com.calles.platform.audit.interfaces.http.dto.AuditRequests;
import com.calles.platform.audit.interfaces.http.dto.AuditResponses;
import com.calles.platform.common.core.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理端审核工单控制器 (AdminAuditController)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：面向风控运营后台、审核员与管理员的工单管理与人工复审接口；</li>
 *   <li><b>核心用例</b>：待人审疑似工单列表检索、多维度违规证据排查、人工审批放行与违规驳回；</li>
 *   <li><b>权限控制</b>：网关前置鉴权校验管理员权限，透传 {@code X-User-Id} 与 {@code X-User-Role}。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/audit/admin")
@RequiredArgsConstructor
public class AdminAuditController {

    /** 审核人工管理与工单应用服务。 */
    private final AuditManualReviewApplicationService manualReviewService;

    /**
     * 平台管理端分页检索审核工单列表。
     *
     * @param query 组合过滤参数 (支持按审核阶段、裁决结果、风险等级和业务 ID 过滤)
     * @return 分页工单响应
     */
    @GetMapping("/tasks")
    public ApiResponse<AuditResponses.TaskPage> listTasks(AuditRequests.TaskQuery query) {
        // 步骤 1：委托应用服务执行复合条件分页检索
        AuditResponses.TaskPage page = manualReviewService.listTasks(query);

        // 步骤 2：返回标准 API 响应
        return ApiResponse.ok(page);
    }

    /**
     * 查询指定审核工单全景详情与多维度机审判定证据。
     *
     * @param id 审核任务主键 ID (UUID 32位)
     * @return 审核任务全景视图响应
     */
    @GetMapping("/tasks/{id}")
    public ApiResponse<AuditResponses.TaskDetail> getTaskDetail(@PathVariable("id") String id) {
        // 步骤 1：委托应用服务按 ID 检索任务与判定明细
        AuditResponses.TaskDetail detail = manualReviewService.getTaskDetail(id);

        // 步骤 2：包装为统一响应
        return ApiResponse.ok(detail);
    }

    /**
     * 执行管理员人工复审裁决（通过或驳回）。
     *
     * <p>终审完结后将自动联动内容微服务，推进视频分级门禁流转。</p>
     *
     * @param id 待审核任务主键 ID
     * @param request 裁决操作请求体 (包含 action: APPROVE / REJECT 与可选驳回原因)
     * @param operatorHeader 从网关鉴权请求头透传的当前操作人 ID (可选)
     * @return 裁决完成后的最新任务全景详情
     */
    @PostMapping("/tasks/{id}/review")
    public ApiResponse<AuditResponses.TaskDetail> reviewTask(
            @PathVariable("id") String id,
            @Valid @RequestBody AuditRequests.ManualReview request,
            @RequestHeader(value = "X-User-Id", defaultValue = "ADMIN_OPERATOR") String operatorHeader
    ) {
        // 步骤 1：提取当前管理员操作身份标识
        String operatorId = (operatorHeader != null && !operatorHeader.isBlank()) ? operatorHeader : "ADMIN_OPERATOR";

        // 步骤 2：执行人工复审并驱动下游回调
        AuditResponses.TaskDetail updatedDetail = manualReviewService.reviewTask(id, request, operatorId);

        // 步骤 3：返回最新结果
        return ApiResponse.ok(updatedDetail);
    }
}
