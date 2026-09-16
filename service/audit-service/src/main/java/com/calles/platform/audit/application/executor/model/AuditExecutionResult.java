package com.calles.platform.audit.application.executor.model;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import lombok.Builder;

import java.util.List;

/**
 * 审核执行器输出判定结果明细模型。
 *
 * <p>封装业务专属审核器综合裁决风险等级、终审摘要以及各底层引擎产出的明细快照。</p>
 */
@Builder
public record AuditExecutionResult(
        ReviewLevel overallLevel,
        String summaryReason,
        List<EngineAuditResult> details
) {
}
