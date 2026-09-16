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
    @DisplayName("supports 仅对 VIDEO 业务类型生效")
    void supportsOnlyVideo() {
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
}
