package com.calles.platform.audit.domain.engine;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;

/**
 * 文本审查引擎接口规范。
 *
 * <p>继承自 {@link AuditEngine} 顶层契约，支持对视频标题、描述、简介或标签文本执行合规审查。</p>
 */
public interface TextAuditEngine extends AuditEngine {

    @Override
    default AuditDimension getDimension() {
        return AuditDimension.TEXT;
    }

    /**
     * 对输入文本执行安全合规审查（指定审查维度）。
     *
     * @param text 待审文本（如标题、简介、标签）
     * @param dimension 审查维度
     * @return 审查判定明细结果
     */
    EngineAuditResult audit(String text, AuditDimension dimension);

    /**
     * 对输入文本执行安全合规审查（默认文本维度）。
     *
     * @param text 待审文本
     * @return 审查判定明细结果
     */
    default EngineAuditResult audit(String text) {
        return audit(text, AuditDimension.TEXT);
    }
}
