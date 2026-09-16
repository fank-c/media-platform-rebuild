package com.calles.platform.audit.application.executor.impl;

import com.calles.platform.audit.application.executor.model.AuditContext;
import com.calles.platform.audit.application.executor.model.AuditExecutionResult;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.engine.ImageAuditEngine;
import com.calles.platform.audit.domain.engine.TextAuditEngine;
import com.calles.platform.audit.domain.engine.VideoAuditEngine;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import com.calles.platform.audit.domain.service.AuditDecisionAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 视频审核执行器 {@link VideoAuditExecutor} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class VideoAuditExecutorTest {

    @Mock
    private TextAuditEngine textAuditEngine;

    @Mock
    private ImageAuditEngine imageAuditEngine;

    @Mock
    private VideoAuditEngine videoAuditEngine;

    private AuditDecisionAggregator decisionAggregator = new AuditDecisionAggregator();

    private VideoAuditExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new VideoAuditExecutor(
                textAuditEngine,
                imageAuditEngine,
                videoAuditEngine,
                decisionAggregator
        );
    }

    @Test
    @DisplayName("getBizType 与 supports 准确绑定 VIDEO 业务类型")
    void shouldBindVideoBizType() {
        assertThat(executor.getBizType()).isEqualTo(com.calles.platform.audit.application.executor.model.AuditBizType.VIDEO);
        assertThat(executor.supports(com.calles.platform.audit.application.executor.model.AuditBizType.VIDEO)).isTrue();
        assertThat(executor.supports(com.calles.platform.audit.application.executor.model.AuditBizType.COMMENT)).isFalse();
        assertThat(executor.supports("VIDEO")).isTrue();
        assertThat(executor.supports("video")).isTrue();
        assertThat(executor.supports("COMMENT")).isFalse();
        assertThat(executor.supports("AVATAR")).isFalse();
    }

    @Test
    @DisplayName("视频各项审查合规时，返回 NORMAL 级别与成功摘要")
    void normalVideoExecution() {
        AuditContext context = AuditContext.forVideo(
                "task_01", "v_01", "cv_01", "u_01", "合规标题", "简介文本", "f_cover", "f_video"
        );

        when(textAuditEngine.audit(eq("合规标题"), any()))
                .thenReturn(EngineAuditResult.normal(AuditDimension.TEXT, "LOCAL_DFA", "标题合规"));
        when(textAuditEngine.audit(eq("简介文本"), any()))
                .thenReturn(EngineAuditResult.normal(AuditDimension.TEXT, "LOCAL_DFA", "简介合规"));
        when(imageAuditEngine.auditCover("f_cover"))
                .thenReturn(EngineAuditResult.normal(AuditDimension.IMAGE, "RULE_IMAGE", "封面合规"));
        when(videoAuditEngine.auditVideo("f_video"))
                .thenReturn(EngineAuditResult.normal(AuditDimension.VIDEO, "RULE_VIDEO", "视频合规"));

        AuditExecutionResult result = executor.execute(context);

        assertThat(result.overallLevel()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(result.details()).hasSize(4);
        assertThat(result.summaryReason()).contains("合规正常");
    }

    @Test
    @DisplayName("标题命中违禁词时，综合裁决为 ILLEGAL")
    void illegalTitleExecution() {
        AuditContext context = AuditContext.forVideo(
                "task_02", "v_02", "cv_02", "u_01", "含枪支弹药", null, "f_cover", "f_video"
        );

        when(textAuditEngine.audit(eq("含枪支弹药"), any()))
                .thenReturn(EngineAuditResult.of(AuditDimension.TEXT, "LOCAL_DFA", ReviewLevel.ILLEGAL,
                        BigDecimal.valueOf(99.00), List.of("枪支弹药"), "标题包含枪支弹药违禁词"));
        when(imageAuditEngine.auditCover("f_cover"))
                .thenReturn(EngineAuditResult.normal(AuditDimension.IMAGE, "RULE_IMAGE", "封面合规"));
        when(videoAuditEngine.auditVideo("f_video"))
                .thenReturn(EngineAuditResult.normal(AuditDimension.VIDEO, "RULE_VIDEO", "视频合规"));

        AuditExecutionResult result = executor.execute(context);

        assertThat(result.overallLevel()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(result.summaryReason()).contains("枪支弹药");
    }

    @Test
    @DisplayName("命中历史通过判定时，直接复用结果且跳过引擎计算")
    void shouldReuseHistoricalApprovedResultsWithoutCallingEngine() {
        EngineAuditResult reusedCover = EngineAuditResult.normal(AuditDimension.IMAGE, "RULE_IMAGE", "[免审复用] 历史合规");
        EngineAuditResult reusedVideo = EngineAuditResult.normal(AuditDimension.VIDEO, "RULE_VIDEO", "[免审复用] 历史合规");

        AuditContext context = AuditContext.forVideo(
                "task_03", "v_03", "cv_03", "u_01", "新修改的合规标题", null, "f_cover_old", "f_video_old",
                java.util.Map.of(
                        AuditDimension.IMAGE, reusedCover,
                        AuditDimension.VIDEO, reusedVideo
                )
        );

        when(textAuditEngine.audit(eq("新修改的合规标题"), any()))
                .thenReturn(EngineAuditResult.normal(AuditDimension.TEXT, "LOCAL_DFA", "文本正常"));

        AuditExecutionResult result = executor.execute(context);

        assertThat(result.overallLevel()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(result.details()).hasSize(3);

        // 验证封面与视频引擎被完全跳过
        org.mockito.Mockito.verify(imageAuditEngine, org.mockito.Mockito.never()).auditCover(any());
        org.mockito.Mockito.verify(videoAuditEngine, org.mockito.Mockito.never()).auditVideo(any());
    }

    @Test
    @DisplayName("引擎执行崩溃时，自动降级为 SUSPICIOUS 人工复审")
    void shouldFallbackToSuspiciousWhenEngineThrowsException() {
        AuditContext context = AuditContext.forVideo(
                "task_04", "v_04", "cv_04", "u_01", "合规标题", null, "f_cover", "f_video"
        );

        when(textAuditEngine.audit(any(), any()))
                .thenReturn(EngineAuditResult.normal(AuditDimension.TEXT, "LOCAL_DFA", "文本正常"));
        when(imageAuditEngine.auditCover("f_cover"))
                .thenReturn(EngineAuditResult.normal(AuditDimension.IMAGE, "RULE_IMAGE", "封面正常"));
        // 模拟视频抽帧服务异常崩溃
        when(videoAuditEngine.auditVideo("f_video"))
                .thenThrow(new RuntimeException("FFmpeg 连接超时或远程机审服务断连"));

        AuditExecutionResult result = executor.execute(context);

        assertThat(result.overallLevel()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(result.summaryReason()).contains("视频机审异常自动降级");
    }
}
