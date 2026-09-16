package com.calles.platform.audit.infrastructure.engine.impl;

import com.calles.platform.audit.domain.engine.VideoAuditEngine;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.infrastructure.engine.base.AbstractRuleAssetAuditEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 默认视频资产规则审查引擎基础设施实现。
 *
 * <p>继承自 {@link AbstractRuleAssetAuditEngine}，实现 {@link VideoAuditEngine}，对主视频流文件资产的存在性及违规特征进行审查。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "audit.aliyun.enabled", havingValue = "false", matchIfMissing = true)
public class DefaultVideoAuditEngine extends AbstractRuleAssetAuditEngine implements VideoAuditEngine {

    /** 引擎标识名称。 */
    public static final String ENGINE_NAME = "RULE_VIDEO";

    private static final List<String> ILLEGAL_KEYWORDS = List.of("illegal", "violation");
    private static final List<String> SUSPICIOUS_KEYWORDS = List.of("suspicious");

    @Override
    public AuditDimension getDimension() {
        return AuditDimension.VIDEO;
    }

    @Override
    public String getEngineType() {
        return ENGINE_NAME;
    }

    @Override
    public EngineAuditResult audit(String videoFileId) {
        return auditAsset(videoFileId);
    }

    @Override
    protected String getMissingTag() {
        return "MISSING_VIDEO_FILE";
    }

    @Override
    protected String getMissingReason() {
        return "缺少主视频文件资产，审查不通过";
    }

    @Override
    protected List<String> getIllegalKeywords() {
        return ILLEGAL_KEYWORDS;
    }

    @Override
    protected String getIllegalTag() {
        return "VIDEO_CONTENT_ILLEGAL";
    }

    @Override
    protected String getIllegalReason() {
        return "视频画面包含严重违规内容 (测试桩检测)";
    }

    @Override
    protected BigDecimal getIllegalConfidence() {
        return BigDecimal.valueOf(99.00);
    }

    @Override
    protected List<String> getSuspiciousKeywords() {
        return SUSPICIOUS_KEYWORDS;
    }

    @Override
    protected String getSuspiciousTag() {
        return "VIDEO_CONTENT_SUSPICIOUS";
    }

    @Override
    protected String getSuspiciousReason() {
        return "视频画面疑似违规，需人工抽检查验";
    }

    @Override
    protected BigDecimal getSuspiciousConfidence() {
        return BigDecimal.valueOf(65.00);
    }

    @Override
    protected String getNormalReason() {
        return "视频多媒体资产基础合规校验通过";
    }
}
