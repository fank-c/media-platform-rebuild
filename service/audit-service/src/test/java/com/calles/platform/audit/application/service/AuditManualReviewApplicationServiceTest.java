package com.calles.platform.audit.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.repository.AuditDetailRepository;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import com.calles.platform.audit.exception.AuditException;
import com.calles.platform.audit.infrastructure.persistence.entity.AuditTaskPO;
import com.calles.platform.audit.infrastructure.persistence.mapper.AuditTaskMapper;
import com.calles.platform.audit.interfaces.http.dto.AuditRequests;
import com.calles.platform.audit.interfaces.http.dto.AuditResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 人工审核用例编排服务 {@link AuditManualReviewApplicationService} 单元测试。
 *
 * <p>测试目标：验证工单全景查询、多条件分页及人工复审审批/驳回状态机跃迁与下游回调联动。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditManualReviewApplicationServiceTest {

    @Mock
    private AuditTaskRepository auditTaskRepository;

    @Mock
    private AuditDetailRepository auditDetailRepository;

    @Mock
    private AuditTaskMapper auditTaskMapper;

    @Mock
    private AuditCallbackService callbackService;

    @InjectMocks
    private AuditManualReviewApplicationService manualReviewService;

    private AuditTask pendingTask;

    @BeforeEach
    void setUp() {
        // 构造一个处于 MANUAL_PENDING 状态的审核任务聚合根
        pendingTask = AuditTask.createVideoAuditTask(
                "v_test_01", "cv_test_01", "u_creator", "疑似测试标题", "简介", "f_cover_suspicious", "f_video_01"
        );
        pendingTask.startMachineAudit();
        pendingTask.completeMachineAudit(ReviewLevel.SUSPICIOUS, "封面疑似低俗，转入人工复审池");
    }

    @Test
    @DisplayName("getTaskDetail 正常检索任务与明细返回全景 DTO")
    void getTaskDetailSuccess() {
        // Given (仓储命中任务与明细数据)
        AuditDetail detail = AuditDetail.of(
                pendingTask.getId(), AuditDimension.IMAGE, "RULE_IMAGE", ReviewLevel.SUSPICIOUS,
                BigDecimal.valueOf(70.00), "疑似低俗", "命中低俗规则"
        );
        when(auditTaskRepository.findById(pendingTask.getId())).thenReturn(Optional.of(pendingTask));
        when(auditDetailRepository.findByTaskId(pendingTask.getId())).thenReturn(List.of(detail));

        // When (执行用例服务检索)
        AuditResponses.TaskDetail result = manualReviewService.getTaskDetail(pendingTask.getId());

        // Then (验证聚合出参结构与字段内容)
        assertThat(result).isNotNull();
        assertThat(result.task().bizId()).isEqualTo("v_test_01");
        assertThat(result.task().stage()).isEqualTo("MANUAL_PENDING");
        assertThat(result.details()).hasSize(1);
        assertThat(result.details().get(0).dimension()).isEqualTo("IMAGE");
    }

    @Test
    @DisplayName("getTaskDetail 任务不存在时抛出 404 AuditException")
    void getTaskDetailNotFound() {
        // Given (仓储未命中任务)
        when(auditTaskRepository.findById("non_existent")).thenReturn(Optional.empty());

        // When & Then (断言抛出 NotFound 业务异常)
        assertThatThrownBy(() -> manualReviewService.getTaskDetail("non_existent"))
                .isInstanceOf(AuditException.class)
                .hasMessageContaining("未找到指定的审核任务")
                .extracting(ex -> ((AuditException) ex).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("listTasks 组合条件分页查询成功并返回 DTO 分页包装")
    void listTasksSuccess() {
        // Given (Mock Mapper 物理分页结果)
        AuditTaskPO po = AuditTaskPO.fromDomain(pendingTask);
        Page<AuditTaskPO> pageResult = new Page<>(1, 10);
        pageResult.setRecords(List.of(po));
        pageResult.setTotal(1L);

        when(auditTaskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(pageResult);

        AuditRequests.TaskQuery query = new AuditRequests.TaskQuery(
                "MANUAL_PENDING", null, "SUSPICIOUS", "v_test_01", 1, 10
        );

        // When (发起分页查询)
        AuditResponses.TaskPage result = manualReviewService.listTasks(query);

        // Then (验证记录映射与总数)
        assertThat(result).isNotNull();
        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).id()).isEqualTo(pendingTask.getId());
    }

    @Test
    @DisplayName("reviewTask 人工审批放行成功，状态变为 FINISHED/PASSED，并联动回调内容微服务")
    void reviewTaskApproveSuccess() {
        // Given (设置处于待人审的任务)
        when(auditTaskRepository.findById(pendingTask.getId())).thenReturn(Optional.of(pendingTask));
        when(auditDetailRepository.findByTaskId(pendingTask.getId())).thenReturn(List.of());

        AuditRequests.ManualReview request = new AuditRequests.ManualReview(
                "APPROVE", "经人工核对无违规，予以放行"
        );

        // When (执行人工放行)
        AuditResponses.TaskDetail result = manualReviewService.reviewTask(pendingTask.getId(), request, "admin_user_99");

        // Then (验证任务状态跃迁、落库与回调)
        assertThat(result.task().stage()).isEqualTo("FINISHED");
        assertThat(result.task().result()).isEqualTo("PASSED");
        assertThat(result.task().operatorId()).isEqualTo("admin_user_99");

        verify(auditTaskRepository).updateById(pendingTask);
        verify(callbackService).callbackContentService(pendingTask);
    }

    @Test
    @DisplayName("reviewTask 人工违规驳回成功，状态变为 FINISHED/REJECTED，记录驳回理由并联动回调")
    void reviewTaskRejectSuccess() {
        // Given (设置处于待人审的任务)
        when(auditTaskRepository.findById(pendingTask.getId())).thenReturn(Optional.of(pendingTask));
        when(auditDetailRepository.findByTaskId(pendingTask.getId())).thenReturn(List.of());

        AuditRequests.ManualReview request = new AuditRequests.ManualReview(
                "REJECT", "封面包含严重违规图样，予以驳回"
        );

        // When (执行人工驳回)
        AuditResponses.TaskDetail result = manualReviewService.reviewTask(pendingTask.getId(), request, "admin_user_99");

        // Then (验证状态为驳回并记录理由)
        assertThat(result.task().stage()).isEqualTo("FINISHED");
        assertThat(result.task().result()).isEqualTo("REJECTED");
        assertThat(result.task().rejectReason()).isEqualTo("封面包含严重违规图样，予以驳回");
        assertThat(result.task().operatorId()).isEqualTo("admin_user_99");

        verify(auditTaskRepository).updateById(pendingTask);
        verify(callbackService).callbackContentService(pendingTask);
    }

    @Test
    @DisplayName("reviewTask 任务处于非待人审状态时拒绝审批，抛出 400 业务异常")
    void reviewTaskIllegalState() {
        // Given (任务已完结 FINISHED)
        pendingTask.approveByManual("SYSTEM");
        when(auditTaskRepository.findById(pendingTask.getId())).thenReturn(Optional.of(pendingTask));

        AuditRequests.ManualReview request = new AuditRequests.ManualReview("APPROVE", null);

        // When & Then (断言抛出 400 异常且不触发更新与回调)
        assertThatThrownBy(() -> manualReviewService.reviewTask(pendingTask.getId(), request, "admin_user_99"))
                .isInstanceOf(AuditException.class)
                .hasMessageContaining("仅处于人工复审中(MANUAL_PENDING)的工单允许裁决")
                .extracting(ex -> ((AuditException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(auditTaskRepository, never()).updateById(any());
        verify(callbackService, never()).callbackContentService(any());
    }

    @Test
    @DisplayName("reviewTask 驳回操作未提供理由时抛出 400 业务异常")
    void reviewTaskRejectMissingReason() {
        // Given (驳回请求未填理由)
        when(auditTaskRepository.findById(pendingTask.getId())).thenReturn(Optional.of(pendingTask));

        AuditRequests.ManualReview request = new AuditRequests.ManualReview("REJECT", "   ");

        // When & Then (断言抛出 400 驳回原因必填异常)
        assertThatThrownBy(() -> manualReviewService.reviewTask(pendingTask.getId(), request, "admin_user_99"))
                .isInstanceOf(AuditException.class)
                .hasMessageContaining("人工驳回时必须填写驳回原因说明")
                .extracting(ex -> ((AuditException) ex).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(auditTaskRepository, never()).updateById(any());
        verify(callbackService, never()).callbackContentService(any());
    }
}
