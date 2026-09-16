package com.calles.platform.audit.domain.model;

import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.enums.CallbackStatus;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditTaskTest {

    @Test
    @DisplayName("工厂方法创建的初始任务处于 RECEIVED 与 PENDING 状态")
    void initialTaskShouldBeReceivedAndPending() {
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_001", "cv_abc123", "u_001", "标题", "简介", "f_cover", "f_video"
        );

        assertThat(task.getStage()).isEqualTo(AuditStage.RECEIVED);
        assertThat(task.getResult()).isEqualTo(AuditResult.PENDING);
        assertThat(task.getReviewLevel()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(task.getCallbackStatus()).isEqualTo(CallbackStatus.PENDING);
        assertThat(task.getCallbackRetries()).isEqualTo(0);
    }

    @Test
    @DisplayName("机审流转：合规放行跃迁至 FINISHED 和 PASSED")
    void machineAuditNormalPass() {
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_001", "cv_abc123", "u_001", "合规标题", "简介", "f_cover", "f_video"
        );
        task.startMachineAudit();
        assertThat(task.getStage()).isEqualTo(AuditStage.MACHINE_AUDITING);

        task.completeMachineAudit(ReviewLevel.NORMAL, null);
        assertThat(task.getStage()).isEqualTo(AuditStage.FINISHED);
        assertThat(task.getResult()).isEqualTo(AuditResult.PASSED);
        assertThat(task.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("机审流转：违禁阻断直接驳回至 FINISHED 和 REJECTED")
    void machineAuditIllegalReject() {
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_001", "cv_abc123", "u_001", "违禁标题", "简介", "f_cover", "f_video"
        );
        task.startMachineAudit();

        task.completeMachineAudit(ReviewLevel.ILLEGAL, "标题包含枪支弹药违规词");
        assertThat(task.getStage()).isEqualTo(AuditStage.FINISHED);
        assertThat(task.getResult()).isEqualTo(AuditResult.REJECTED);
        assertThat(task.getRejectReason()).isEqualTo("标题包含枪支弹药违规词");
    }

    @Test
    @DisplayName("机审流转：疑似违规挂起至 MANUAL_PENDING 并等待人工审批")
    void machineAuditSuspiciousEscalateToManual() {
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_001", "cv_abc123", "u_001", "疑似标题", "简介", "f_cover", "f_video"
        );
        task.startMachineAudit();

        task.completeMachineAudit(ReviewLevel.SUSPICIOUS, "疑似含有低俗暗示");
        assertThat(task.getStage()).isEqualTo(AuditStage.MANUAL_PENDING);
        assertThat(task.getResult()).isEqualTo(AuditResult.PENDING);

        // 人工审批通过
        task.approveByManual("admin_999");
        assertThat(task.getStage()).isEqualTo(AuditStage.FINISHED);
        assertThat(task.getResult()).isEqualTo(AuditResult.PASSED);
        assertThat(task.getOperatorId()).isEqualTo("admin_999");
    }

    @Test
    @DisplayName("机审未启动时直接完成机审应抛出异常")
    void invalidStateTransitionShouldThrow() {
        AuditTask task = AuditTask.createVideoAuditTask(
                "v_001", "cv_abc123", "u_001", "标题", "简介", "f_cover", "f_video"
        );

        assertThatThrownBy(() -> task.completeMachineAudit(ReviewLevel.NORMAL, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
