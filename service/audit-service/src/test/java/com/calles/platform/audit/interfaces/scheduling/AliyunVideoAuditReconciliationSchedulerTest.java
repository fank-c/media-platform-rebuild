package com.calles.platform.audit.interfaces.scheduling;

import com.calles.platform.audit.application.service.AuditCallbackService;
import com.calles.platform.audit.config.aliyun.AliyunGreenProperties;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.repository.AuditDetailRepository;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import com.calles.platform.audit.domain.service.AuditDecisionAggregator;
import com.calles.platform.audit.infrastructure.engine.aliyun.AliyunGreenVideoAuditEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阿里云视频机审定时对账调度器 (AliyunVideoAuditReconciliationScheduler) 单元测试。
 *
 * <p>涵盖以下核心业务路径：
 * <ul>
 *   <li>场景 1：探针探测到已完成 ➔ 状态机推进至终态 FINISHED 并触发下游微服务回调；</li>
 *   <li>场景 2：未到期且云端未就绪 ➔ 保持机审中状态，等待下一轮对账轮询；</li>
 *   <li>场景 3：已超过动态 SLA 截止时刻 ➔ 优雅降级为 MANUAL_PENDING 转入人工复审池；</li>
 *   <li>场景 4：任务创建不足 3 秒的保护窗口 ➔ 跳过探针探测，防瞬时并发冲突；</li>
 *   <li>场景 5：功能总开关关闭 ➔ 直接返回，不发起数据库查询。</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AliyunVideoAuditReconciliationSchedulerTest {

    @Mock
    private AuditTaskRepository auditTaskRepository;

    @Mock
    private AuditDetailRepository auditDetailRepository;

    @Mock
    private ObjectProvider<AliyunGreenVideoAuditEngine> videoAuditEngineProvider;

    @Mock
    private AliyunGreenVideoAuditEngine videoAuditEngine;

    @Mock
    private AuditDecisionAggregator decisionAggregator;

    @Mock
    private AuditCallbackService callbackService;

    private AliyunGreenProperties properties;
    private AliyunVideoAuditReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new AliyunGreenProperties();
        properties.setEnabled(true);
        lenient().when(videoAuditEngineProvider.getIfAvailable()).thenReturn(videoAuditEngine);
        scheduler = new AliyunVideoAuditReconciliationScheduler(
                auditTaskRepository,
                auditDetailRepository,
                videoAuditEngineProvider,
                decisionAggregator,
                callbackService,
                properties
        );
    }

    @Test
    @DisplayName("场景 1：探针探测到已完成，推进终局并触发下游回调")
    void shouldCompleteAuditAndCallbackWhenProbeSucceeds() {
        // 准备处于 MACHINE_AUDITING 且创建满 10 秒的任务
        AuditTask task = AuditTask.builder()
                .id("task_001")
                .taskNo("aud_001")
                .stage(AuditStage.MACHINE_AUDITING)
                .result(AuditResult.PENDING)
                .createdAt(Instant.now().minusSeconds(10))
                .build();

        String detailLog = "ALIYUN_TASK_ID:aliyun_task_123|DEADLINE:" + Instant.now().plusSeconds(60).toString();
        AuditDetail videoDetail = AuditDetail.builder()
                .id("detail_001")
                .taskId("task_001")
                .dimension(AuditDimension.VIDEO)
                .detailLog(detailLog)
                .build();

        when(auditTaskRepository.findRunningMachineAuditTasks(50)).thenReturn(List.of(task));
        when(auditDetailRepository.findByTaskId("task_001")).thenReturn(List.of(videoDetail));

        EngineAuditResult probeResult = EngineAuditResult.normal(AuditDimension.VIDEO, "ALIYUN_GREEN", "视频机审通过");
        when(videoAuditEngine.queryVideoModerationResult("aliyun_task_123")).thenReturn(probeResult);

        when(decisionAggregator.aggregate(any())).thenReturn(
                new AuditDecisionAggregator.Decision(ReviewLevel.NORMAL, "各项审查均合规正常", List.of(probeResult))
        );

        // 执行对账
        scheduler.reconcileVideoAudits();

        // 验证状态变更为终态 FINISHED，结果为 PASSED
        assertThat(task.getStage()).isEqualTo(AuditStage.FINISHED);
        assertThat(task.getResult()).isEqualTo(AuditResult.PASSED);

        // 验证持久化与 Feign 回调
        verify(auditDetailRepository).insert(any(AuditDetail.class));
        verify(auditTaskRepository).updateById(task);
        verify(callbackService).callbackContentService(task);
    }

    @Test
    @DisplayName("场景 2：未到期且云端未完成，保持机审中等待下一轮")
    void shouldKeepMachineAuditingWhenProbeReturnsNull() {
        AuditTask task = AuditTask.builder()
                .id("task_002")
                .taskNo("aud_002")
                .stage(AuditStage.MACHINE_AUDITING)
                .result(AuditResult.PENDING)
                .createdAt(Instant.now().minusSeconds(10))
                .build();

        String detailLog = "ALIYUN_TASK_ID:aliyun_task_456|DEADLINE:" + Instant.now().plusSeconds(60).toString();
        AuditDetail videoDetail = AuditDetail.builder()
                .id("detail_002")
                .taskId("task_002")
                .dimension(AuditDimension.VIDEO)
                .detailLog(detailLog)
                .build();

        when(auditTaskRepository.findRunningMachineAuditTasks(50)).thenReturn(List.of(task));
        when(auditDetailRepository.findByTaskId("task_002")).thenReturn(List.of(videoDetail));
        when(videoAuditEngine.queryVideoModerationResult("aliyun_task_456")).thenReturn(null);

        scheduler.reconcileVideoAudits();

        // 状态保持机审中，不触发任何终结更新或回调
        assertThat(task.getStage()).isEqualTo(AuditStage.MACHINE_AUDITING);
        assertThat(task.getResult()).isEqualTo(AuditResult.PENDING);
        verify(auditTaskRepository, never()).updateById(any());
        verify(callbackService, never()).callbackContentService(any());
    }

    @Test
    @DisplayName("场景 3：超过 SLA 截止时间，优雅降级为 MANUAL_PENDING 转入人工复审")
    void shouldDegradeToManualPendingWhenDeadlineExceeded() {
        AuditTask task = AuditTask.builder()
                .id("task_003")
                .taskNo("aud_003")
                .stage(AuditStage.MACHINE_AUDITING)
                .result(AuditResult.PENDING)
                .createdAt(Instant.now().minusSeconds(100))
                .build();

        // DEADLINE 已过期 10 秒
        String detailLog = "ALIYUN_TASK_ID:aliyun_task_789|DEADLINE:" + Instant.now().minusSeconds(10).toString();
        AuditDetail videoDetail = AuditDetail.builder()
                .id("detail_003")
                .taskId("task_003")
                .dimension(AuditDimension.VIDEO)
                .detailLog(detailLog)
                .build();

        when(auditTaskRepository.findRunningMachineAuditTasks(50)).thenReturn(List.of(task));
        when(auditDetailRepository.findByTaskId("task_003")).thenReturn(List.of(videoDetail));

        scheduler.reconcileVideoAudits();

        // 验证降级转为人工复审池
        assertThat(task.getStage()).isEqualTo(AuditStage.MANUAL_PENDING);
        assertThat(task.getReviewLevel()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(task.getRejectReason()).contains("超过动态时限");

        // 验证仅更新任务，不查询探针，且未终审时不触发下游回调
        verify(videoAuditEngine, never()).queryVideoModerationResult(any());
        verify(auditTaskRepository).updateById(task);
        verify(callbackService, never()).callbackContentService(any());
    }

    @Test
    @DisplayName("场景 4：任务创建不足 3 秒的保护窗口，跳过探针查询")
    void shouldSkipTaskWhenCreatedWithinProtectionWindow() {
        AuditTask task = AuditTask.builder()
                .id("task_004")
                .taskNo("aud_004")
                .stage(AuditStage.MACHINE_AUDITING)
                .result(AuditResult.PENDING)
                .createdAt(Instant.now().minusSeconds(1)) // 仅创建 1 秒
                .build();

        when(auditTaskRepository.findRunningMachineAuditTasks(50)).thenReturn(List.of(task));

        scheduler.reconcileVideoAudits();

        // 验证保护窗口生效，未查询明细与探针
        verify(auditDetailRepository, never()).findByTaskId(any());
        verify(videoAuditEngine, never()).queryVideoModerationResult(any());
    }

    @Test
    @DisplayName("场景 5：阿里云总开关关闭时，直接退出不执行任何拉取")
    void shouldDoNothingWhenAliyunAuditDisabled() {
        properties.setEnabled(false);

        scheduler.reconcileVideoAudits();

        verify(auditTaskRepository, never()).findRunningMachineAuditTasks(anyInt());
    }
}
