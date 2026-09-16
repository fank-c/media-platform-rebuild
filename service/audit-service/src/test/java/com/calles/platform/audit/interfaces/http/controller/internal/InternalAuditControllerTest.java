package com.calles.platform.audit.interfaces.http.controller.internal;

import com.calles.platform.audit.application.coordinator.AuditTaskCoordinator;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部端审核控制器 {@link InternalAuditController} 单元测试用例。
 *
 * <p>测试目标：验证微服务内部调试提交与任务详情检索接口在正向、异常分支下的契约序列化与状态码行为。</p>
 */
@WebMvcTest(InternalAuditController.class)
@Import(AuditExceptionHandler.class)
class InternalAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuditTaskCoordinator auditTaskCoordinator;

    @MockBean
    private AuditManualReviewApplicationService manualReviewService;

    @Test
    @DisplayName("POST /api/audit/internal/submit 模拟发起审核流水线成功并正确返回 DTO")
    void mockSubmitSuccess() throws Exception {
        // Given (准备提审实体与模拟返回)
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_100", "cv_test100", "u_001", "合规视频", "简介", "f_cover", "f_video"
        );
        task.startMachineAudit();
        task.completeMachineAudit(ReviewLevel.NORMAL, null);

        when(auditTaskCoordinator.processVideoSubmission(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task);

        AuditRequests.MockSubmit req = new AuditRequests.MockSubmit(
                "v_100", "cv_test100", "u_001", "合规视频", "简介", "f_cover", "f_video"
        );

        // When & Then (发起请求并断言 DTO 字段映射)
        mockMvc.perform(post("/api/audit/internal/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bizId").value("v_100"))
                .andExpect(jsonPath("$.data.stage").value("FINISHED"))
                .andExpect(jsonPath("$.data.result").value("PASSED"));
    }

    @Test
    @DisplayName("GET /api/audit/tasks/{id} 查询审核任务及判定明细返回完整全景 DTO")
    void getTaskDetailSuccess() throws Exception {
        // Given (构造审核全景详情 DTO 并 Mock 应用服务)
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_100", "cv_test100", "u_001", "合规视频", "简介", "f_cover", "f_video"
        );
        AuditDetail detail = AuditDetail.of(
                task.getId(), AuditDimension.TEXT, "LOCAL_DFA", ReviewLevel.NORMAL,
                BigDecimal.valueOf(100.00), null, "文本合规"
        );

        AuditResponses.TaskDetail taskDetail = AuditResponses.TaskDetail.of(task, List.of(detail));
        when(manualReviewService.getTaskDetail("task_123")).thenReturn(taskDetail);

        // When & Then (发起查询并断言全景结构)
        mockMvc.perform(get("/api/audit/tasks/task_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.task.bizId").value("v_100"))
                .andExpect(jsonPath("$.data.details[0].dimension").value("TEXT"));
    }

    @Test
    @DisplayName("GET /api/audit/tasks/{id} 任务不存在时经异常处理器返回 404")
    void getTaskDetailNotFound() throws Exception {
        // Given (Mock 应用服务抛出 NotFound 业务受控异常)
        when(manualReviewService.getTaskDetail("non_existent"))
                .thenThrow(AuditException.notFound("审核任务不存在: non_existent"));

        // When & Then (断言 HTTP 404 状态码及错误信息)
        mockMvc.perform(get("/api/audit/tasks/non_existent"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("审核任务不存在: non_existent"));
    }
}
