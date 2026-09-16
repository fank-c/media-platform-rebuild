package com.calles.platform.audit.infrastructure.engine.impl;

import com.calles.platform.audit.domain.engine.ImageAuditEngine;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.infrastructure.engine.base.AbstractRuleAssetAuditEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 默认多媒体封面规则审查引擎（支持测试桩模拟与可插拔第三方云检测扩展）。
 *
 * <p>继承自 {@link AbstractRuleAssetAuditEngine}，实现 {@link ImageAuditEngine}，负责对封面、插图等多媒体图像资产进行合规规则检测。</p>
 */
@Slf4j
@Component
public class DefaultRuleImageAuditEngine extends AbstractRuleAssetAuditEngine implements ImageAuditEngine {

    /** 引擎标识名称。 */
    public static final String ENGINE_NAME = "RULE_IMAGE";

    private static final List<String> ILLEGAL_KEYWORDS = List.of("illegal", "porn", "violation");
    private static final List<String> SUSPICIOUS_KEYWORDS = List.of("suspicious");

    @Override
    public AuditDimension getDimension() {
        return AuditDimension.IMAGE;
    }

    @Override
    public String getEngineType() {
        return ENGINE_NAME;
    }

    @Override
    public EngineAuditResult audit(String imageFileId) {
        return auditAsset(imageFileId);
    }

    @Override
    protected String getMissingTag() {
        return "MISSING_COVER";
    }

    @Override
    protected String getMissingReason() {
        return "视频缺少封面图资产，审查不通过";
    }

    @Override
    protected List<String> getIllegalKeywords() {
        return ILLEGAL_KEYWORDS;
    }

    @Override
    protected String getIllegalTag() {
        return "IMAGE_PORN_OR_VIOLATION";
    }

    @Override
    protected String getIllegalReason() {
        return "封面图片命中严重违规特征库 (测试桩检测)";
    }

    @Override
    protected BigDecimal getIllegalConfidence() {
        return BigDecimal.valueOf(98.50);
    }

    @Override
    protected List<String> getSuspiciousKeywords() {
        return SUSPICIOUS_KEYWORDS;
    }

    @Override
    protected String getSuspiciousTag() {
        return "IMAGE_SUSPICIOUS";
    }

    @Override
    protected String getSuspiciousReason() {
        return "封面图片包含疑似低俗或敏感元素，转人工审核";
    }

    @Override
    protected BigDecimal getSuspiciousConfidence() {
        return BigDecimal.valueOf(70.00);
    }

    @Override
    protected String getNormalReason() {
        return "封面图片合规正常";
    }
}
