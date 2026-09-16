package com.calles.platform.audit.domain.service;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 审核多引擎判定决策仲裁器（领域服务）。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核领域服务层，封装纯粹的业务仲裁计算逻辑；</li>
 *   <li><b>仲裁策略</b>：遵循最高风险优先仲裁原则（{@link ReviewLevel#ILLEGAL} > {@link ReviewLevel#SUSPICIOUS} > {@link ReviewLevel#NORMAL}）；</li>
 *   <li><b>协作对象</b>：接收各审查引擎计算得出的 {@link EngineAuditResult} 明细列表，汇总产出全局最终研判决策。</li>
 * </ul>
 * </p>
 */
@Component
public class AuditDecisionAggregator {

    /**
     * 决策汇总输出模型。
     *
     * @param overallLevel 全局综合判定风险级别
     * @param summaryReason 汇总说明或驳回理由
     * @param details 参与仲裁的各维度引擎判定明细快照
     */
    public record Decision(
            ReviewLevel overallLevel,
            String summaryReason,
            List<EngineAuditResult> details
    ) {}

    /**
     * 汇集所有引擎单项判定，计算全局仲裁结果。
     *
     * @param results 各维度引擎判定结果集合
     * @return 最终聚合决策
     */
    public Decision aggregate(List<EngineAuditResult> results) {
        if (results == null || results.isEmpty()) {
            return new Decision(ReviewLevel.NORMAL, "无审查明细，默认放行", List.of());
        }

        ReviewLevel highestLevel = ReviewLevel.NORMAL;
        List<String> illegalReasons = new ArrayList<>();
        List<String> suspiciousReasons = new ArrayList<>();

        // 步骤 1：遍历评估每一个维度的审查明细
        for (EngineAuditResult res : results) {
            if (res.level() == ReviewLevel.ILLEGAL) {
                highestLevel = ReviewLevel.ILLEGAL;
                illegalReasons.add(res.detailLog());
            } else if (res.level() == ReviewLevel.SUSPICIOUS) {
                if (highestLevel != ReviewLevel.ILLEGAL) {
                    highestLevel = ReviewLevel.SUSPICIOUS;
                }
                suspiciousReasons.add(res.detailLog());
            }
        }

        // 步骤 2：根据最高风险级别生成原因摘要
        String summaryReason;
        if (highestLevel == ReviewLevel.ILLEGAL) {
            summaryReason = String.join("；", illegalReasons);
        } else if (highestLevel == ReviewLevel.SUSPICIOUS) {
            summaryReason = String.join("；", suspiciousReasons);
        } else {
            summaryReason = "各项审查均合规正常";
        }

        return new Decision(highestLevel, summaryReason, results);
    }
}
