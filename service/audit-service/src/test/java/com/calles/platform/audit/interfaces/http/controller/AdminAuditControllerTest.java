package com.calles.platform.audit.interfaces.http.controller;

import com.calles.platform.audit.application.service.AuditManualReviewApplicationService;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.exception.AuditException;
import com.calles.platform.audit.interfaces.http.advice.AuditExceptionHandler;
import com.calles.platform.audit.interfaces.http.dto.AuditRequests;
import com.calles.platform.audit.interfaces.http.dto.AuditResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端审核工作台控制器 {@link AdminAuditController} 单元测试用例。
 *
 * <p>测试目标：验证管理后台在工单分页检索、任务全景透视以及人工审批/驳回决策触发时的接口表现与参数校验。</p>
 */
@WebMvcTest(AdminAuditController.class)
@Import(AuditExceptionHandler.class)
class AdminAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuditManualReviewApplicationService manualReviewService;

    @Test
    @DisplayName("GET /api/audit/admin/tasks 分页检索工单列表成功")
    void queryTasksSuccess() throws Exception {
        // Given (准备待人审工单列表数据)
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_001", "cv_001", "u_admin", "测试标题", "测试简介", "f_cover", "f_video"
        );
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.SUSPICIOUS, "疑似低俗");

        AuditResponses.TaskItem item = AuditResponses.TaskItem.fromDomain(task);
        AuditResponses.TaskPage pageResult = new AuditResponses.TaskPage(List.of(item), 1L, 1, 10);
        when(manualReviewService.listTasks(any(AuditRequests.TaskQuery.class))).thenReturn(pageResult);

        // When & Then (发起条件检索并断言分页结构)
        mockMvc.perform(get("/api/audit/admin/tasks")
                        .param("page", "1")
                        .param("size", "10")
                        .param("stage", "MANUAL_PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(task.getId()))
                .andExpect(jsonPath("$.data.records[0].stage").value("MANUAL_PENDING"));
    }

    @Test
    @DisplayName("GET /api/audit/admin/tasks/{id} 查询工单全景详情成功")
    void getTaskDetailSuccess() throws Exception {
        // Given (准备工单详情 DTO)
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_001", "cv_001", "u_admin", "测试标题", "测试简介", "f_cover", "f_video"
        );
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.SUSPICIOUS, "疑似低俗");

        AuditDetail detail = AuditDetail.of(
                task.getId(), AuditDimension.IMAGE, "RULE_IMAGE", ReviewLevel.SUSPICIOUS,
                BigDecimal.valueOf(70.00), "疑似低俗", "命中低俗规则"
        );
        AuditResponses.TaskDetail fullDetail = AuditResponses.TaskDetail.of(task, List.of(detail));
        when(manualReviewService.getTaskDetail(task.getId())).thenReturn(fullDetail);

        // When & Then (发起详情检索并断言明细)
        mockMvc.perform(get("/api/audit/admin/tasks/" + task.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.task.id").value(task.getId()))
                .andExpect(jsonPath("$.data.details[0].dimension").value("IMAGE"));
    }

    @Test
    @DisplayName("POST /api/audit/admin/tasks/{id}/review 人工放行审批成功")
    void approveTaskSuccess() throws Exception {
        // Given (准备放行请求体)
        AuditRequests.ManualReview req = new AuditRequests.ManualReview(
                "APPROVE", "经人工核查，封面不构成低俗，予以放行"
        );
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_001", "cv_001", "u_admin", "测试标题", "测试简介", "f_cover", "f_video"
        );
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.SUSPICIOUS, "疑似低俗");
        task.approveByManual("op_999");

        AuditResponses.TaskDetail detail = AuditResponses.TaskDetail.of(task, List.of());
        when(manualReviewService.reviewTask(eq(task.getId()), any(AuditRequests.ManualReview.class), eq("op_999")))
                .thenReturn(detail);

        // When & Then (发送审批请求并断言终局状态)
        mockMvc.perform(post("/api/audit/admin/tasks/" + task.getId() + "/review")
                        .header("X-User-Id", "op_999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.task.stage").value("FINISHED"))
                .andExpect(jsonPath("$.data.task.result").value("PASSED"))
                .andExpect(jsonPath("$.data.task.operatorId").value("op_999"));

        verify(manualReviewService).reviewTask(eq(task.getId()), any(AuditRequests.ManualReview.class), eq("op_999"));
    }

    @Test
    @DisplayName("POST /api/audit/admin/tasks/{id}/review 人工阻断驳回成功")
    void rejectTaskSuccess() throws Exception {
        // Given (准备驳回请求体与驳回理由)
        AuditRequests.ManualReview req = new AuditRequests.ManualReview(
                "REJECT", "确认为违禁广告推广，予以驳回"
        );
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_001", "cv_001", "u_admin", "测试标题", "测试简介", "f_cover", "f_video"
        );
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.SUSPICIOUS, "疑似低俗");
        task.rejectByManual("op_999", req.reason());

        AuditResponses.TaskDetail detail = AuditResponses.TaskDetail.of(task, List.of());
        when(manualReviewService.reviewTask(eq(task.getId()), any(AuditRequests.ManualReview.class), eq("op_999")))
                .thenReturn(detail);

        // When & Then (发送驳回请求并断言驳回理由)
        mockMvc.perform(post("/api/audit/admin/tasks/" + task.getId() + "/review")
                        .header("X-User-Id", "op_999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.task.stage").value("FINISHED"))
                .andExpect(jsonPath("$.data.task.result").value("REJECTED"))
                .andExpect(jsonPath("$.data.task.rejectReason").value("确认为违禁广告推广，予以驳回"));

        verify(manualReviewService).reviewTask(eq(task.getId()), any(AuditRequests.ManualReview.class), eq("op_999"));
    }

    @Test
    @DisplayName("POST /api/audit/admin/tasks/{id}/review action 为空或非法时返回 400 校验异常")
    void rejectTaskValidationFailure() throws Exception {
        // Given (提供非法的 action 类型 INVALID_ACTION)
        AuditRequests.ManualReview req = new AuditRequests.ManualReview("INVALID_ACTION", "驳回");

        // When & Then (断言 400 Bad Request)
        mockMvc.perform(post("/api/audit/admin/tasks/task_001/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("action 仅支持 APPROVE 或 REJECT"));
    }
}
