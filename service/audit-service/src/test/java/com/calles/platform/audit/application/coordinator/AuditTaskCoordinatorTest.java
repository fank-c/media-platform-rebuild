package com.calles.platform.audit.application.coordinator;

import com.calles.platform.audit.application.executor.AuditExecutorRouter;
import com.calles.platform.audit.application.executor.impl.VideoAuditExecutor;
import com.calles.platform.audit.application.service.AuditCallbackService;
import com.calles.platform.audit.domain.service.AuditDecisionAggregator;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.engine.ImageAuditEngine;
import com.calles.platform.audit.domain.engine.TextAuditEngine;
import com.calles.platform.audit.domain.engine.VideoAuditEngine;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.repository.AuditDetailRepository;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditTaskCoordinatorTest {

    @Mock
    private AuditTaskRepository auditTaskRepository;

    @Mock
    private AuditDetailRepository auditDetailRepository;

    @Mock
    private TextAuditEngine textAuditEngine;

    @Mock
    private ImageAuditEngine imageAuditEngine;

    @Mock
    private VideoAuditEngine videoAuditEngine;

    @Mock
    private AuditCallbackService callbackService;

    private AuditDecisionAggregator decisionAggregator = new AuditDecisionAggregator();

    private AuditTaskCoordinator coordinator;

    @BeforeEach
    void setUp() {
        VideoAuditExecutor videoAuditExecutor = new VideoAuditExecutor(
                textAuditEngine,
                imageAuditEngine,
                videoAuditEngine,
                decisionAggregator
        );
        AuditExecutorRouter router = new AuditExecutorRouter(List.of(videoAuditExecutor));

        coordinator = new AuditTaskCoordinator(
                auditTaskRepository,
                auditDetailRepository,
                router,
                callbackService
        );
    }

    @Test
    @DisplayName("合规提审：机审全通过，生成证据明细，触发下游回调")
    void normalSubmissionShouldCompleteAndCallback() {
        when(auditTaskRepository.findLatestByBiz("VIDEO", "v_100")).thenReturn(Optional.empty());

        when(textAuditEngine.audit(eq("合规标题"), any()))
                .thenReturn(EngineAuditResult.normal(AuditDimension.TEXT, "LOCAL_DFA", "文本正常"));
        when(imageAuditEngine.auditCover("f_cover"))
                .thenReturn(EngineAuditResult.normal(AuditDimension.IMAGE, "RULE_IMAGE", "封面正常"));
        when(videoAuditEngine.auditVideo("f_video"))
                .thenReturn(EngineAuditResult.normal(AuditDimension.VIDEO, "RULE_VIDEO", "视频正常"));

        AuditTask resultTask = coordinator.processVideoSubmission(
                "v_100", "cv_abc123", "u_001", "合规标题", null, "f_cover", "f_video"
        );

        assertThat(resultTask.getStage()).isEqualTo(AuditStage.FINISHED);
        assertThat(resultTask.getResult()).isEqualTo(AuditResult.PASSED);

        verify(auditTaskRepository).insert(any());
        verify(auditDetailRepository).insertBatch(any());
        verify(callbackService).callbackContentService(any());
    }

    @Test
    @DisplayName("违规提审：标题涉敏被直接拦截，流转至 REJECTED 并回调内容服务")
    void illegalSubmissionShouldBeRejectedAndCallback() {
        when(auditTaskRepository.findLatestByBiz("VIDEO", "v_200")).thenReturn(Optional.empty());

        when(textAuditEngine.audit(eq("含枪支弹药"), any()))
                .thenReturn(EngineAuditResult.of(AuditDimension.TEXT, "LOCAL_DFA", ReviewLevel.ILLEGAL,
                        BigDecimal.valueOf(99.00), List.of("枪支弹药"), "标题包含枪支弹药违禁词"));
        when(imageAuditEngine.auditCover("f_cover"))
                .thenReturn(EngineAuditResult.normal(AuditDimension.IMAGE, "RULE_IMAGE", "封面正常"));
        when(videoAuditEngine.auditVideo("f_video"))
                .thenReturn(EngineAuditResult.normal(AuditDimension.VIDEO, "RULE_VIDEO", "视频正常"));

        AuditTask resultTask = coordinator.processVideoSubmission(
                "v_200", "cv_abc200", "u_001", "含枪支弹药", null, "f_cover", "f_video"
        );

        assertThat(resultTask.getStage()).isEqualTo(AuditStage.FINISHED);
        assertThat(resultTask.getResult()).isEqualTo(AuditResult.REJECTED);
        assertThat(resultTask.getRejectReason()).contains("标题包含枪支弹药违禁词");

        verify(callbackService).callbackContentService(any());
    }

    @Test
    @DisplayName("可疑提审：封面疑似违规转入 MANUAL_PENDING，暂不触发回调")
    void suspiciousSubmissionShouldWaitManualReview() {
        when(auditTaskRepository.findLatestByBiz("VIDEO", "v_300")).thenReturn(Optional.empty());

        when(textAuditEngine.audit(any(), any()))
                .thenReturn(EngineAuditResult.normal(AuditDimension.TEXT, "LOCAL_DFA", "文本正常"));
        when(imageAuditEngine.auditCover("f_cover_suspicious"))
                .thenReturn(EngineAuditResult.of(AuditDimension.IMAGE, "RULE_IMAGE", ReviewLevel.SUSPICIOUS,
                        BigDecimal.valueOf(70.00), List.of("IMAGE_SUSPICIOUS"), "封面疑似低俗需人审"));
        when(videoAuditEngine.auditVideo(any()))
                .thenReturn(EngineAuditResult.normal(AuditDimension.VIDEO, "RULE_VIDEO", "视频正常"));

        AuditTask resultTask = coordinator.processVideoSubmission(
                "v_300", "cv_abc300", "u_001", "普通标题", null, "f_cover_suspicious", "f_video"
        );

        assertThat(resultTask.getStage()).isEqualTo(AuditStage.MANUAL_PENDING);
        assertThat(resultTask.getResult()).isEqualTo(AuditResult.PENDING);

        // 尚未结束审核，禁止回调下游
        verify(callbackService, never()).callbackContentService(any());
    }

    @Test
    @DisplayName("同一视频已有正在处理的任务时，幂等保护直接返回现有任务")
    void duplicateSubmissionShouldBeIdempotent() {
        AuditTask running = AuditTask.createVideoAuditTask(
                "v_100", "cv_test", "u_001", "标题", null, "c", "v"
        );
        running.startMachineAudit();

        when(auditTaskRepository.findLatestByBiz("VIDEO", "v_100")).thenReturn(Optional.of(running));

        AuditTask result = coordinator.processVideoSubmission(
                "v_100", "cv_test", "u_001", "标题", null, "c", "v"
        );

        assertThat(result).isSameAs(running);
        verify(textAuditEngine, never()).audit(any(), any());
    }
}
