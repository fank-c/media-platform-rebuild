package com.calles.platform.audit.domain.service;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核多维度综合裁决领域服务单元测试。
 */
class AuditDecisionAggregatorTest {

    private final AuditDecisionAggregator aggregator = new AuditDecisionAggregator();

    @Test
    @DisplayName("所有维度均 NORMAL 时，综合仲裁结果为 NORMAL")
    void allNormalShouldResultInNormal() {
        List<EngineAuditResult> results = List.of(
                EngineAuditResult.normal(AuditDimension.TEXT, "LOCAL_DFA", "文本正常"),
                EngineAuditResult.normal(AuditDimension.IMAGE, "RULE_IMAGE", "封面正常"),
                EngineAuditResult.normal(AuditDimension.VIDEO, "RULE_VIDEO", "视频正常")
        );

        AuditDecisionAggregator.Decision decision = aggregator.aggregate(results);

        assertThat(decision.overallLevel()).isEqualTo(ReviewLevel.NORMAL);
        assertThat(decision.summaryReason()).contains("合规正常");
    }

    @Test
    @DisplayName("存在任一 ILLEGAL 维度时，最高优先级裁决为 ILLEGAL")
    void anyIllegalShouldResultInIllegal() {
        List<EngineAuditResult> results = List.of(
                EngineAuditResult.of(AuditDimension.TEXT, "LOCAL_DFA", ReviewLevel.ILLEGAL,
                        BigDecimal.valueOf(99.00), List.of("违禁词"), "标题含有严重违禁词"),
                EngineAuditResult.of(AuditDimension.IMAGE, "RULE_IMAGE", ReviewLevel.SUSPICIOUS,
                        BigDecimal.valueOf(70.00), List.of("可疑"), "封面图片疑似低俗"),
                EngineAuditResult.normal(AuditDimension.VIDEO, "RULE_VIDEO", "视频正常")
        );

        AuditDecisionAggregator.Decision decision = aggregator.aggregate(results);

        assertThat(decision.overallLevel()).isEqualTo(ReviewLevel.ILLEGAL);
        assertThat(decision.summaryReason()).contains("标题含有严重违禁词");
    }

    @Test
    @DisplayName("无 ILLEGAL 且包含 SUSPICIOUS 时，综合裁决为 SUSPICIOUS 转人审")
    void suspiciousWithoutIllegalShouldResultInSuspicious() {
        List<EngineAuditResult> results = List.of(
                EngineAuditResult.normal(AuditDimension.TEXT, "LOCAL_DFA", "文本正常"),
                EngineAuditResult.of(AuditDimension.IMAGE, "RULE_IMAGE", ReviewLevel.SUSPICIOUS,
                        BigDecimal.valueOf(65.00), List.of("疑似低俗"), "封面疑似低俗需确认"),
                EngineAuditResult.normal(AuditDimension.VIDEO, "RULE_VIDEO", "视频正常")
        );

        AuditDecisionAggregator.Decision decision = aggregator.aggregate(results);

        assertThat(decision.overallLevel()).isEqualTo(ReviewLevel.SUSPICIOUS);
        assertThat(decision.summaryReason()).contains("封面疑似低俗需确认");
    }
}
