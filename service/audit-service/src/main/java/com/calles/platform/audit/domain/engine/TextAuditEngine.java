package com.calles.platform.audit.domain.engine;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;

/**
 * 文本审查引擎接口规范。
 */
public interface TextAuditEngine {

    /**
     * 对输入文本执行安全合规审查。
     *
     * @param text 待审文本（如标题、简介、标签）
     * @param dimension 审查维度
     * @return 审查判定明细结果
     */
    EngineAuditResult audit(String text, AuditDimension dimension);
}
