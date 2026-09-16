package com.calles.platform.audit.infrastructure.engine.rule;

import com.calles.platform.audit.domain.engine.AuditEngine;
import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.List;

/**
 * 基于预设规则与特征桩的多媒体文件资产审查引擎抽象模板基类。
 *
 * <p>封装针对文件资产 ID/路径的通用检测流水线，采用模板方法模式统一处理：
 * <ol>
 *   <li><b>资产非空校验</b>：缺失资产直接阻断并标定 {@link ReviewLevel#ILLEGAL}；</li>
 *   <li><b>严重违禁特征扫描</b>：命中特征关键字标定 {@link ReviewLevel#ILLEGAL}；</li>
 *   <li><b>疑似风险特征扫描</b>：命中疑似关键字转入人工审核 {@link ReviewLevel#SUSPICIOUS}；</li>
 *   <li><b>合规放行判定</b>：未命中任何违禁特征标定 {@link ReviewLevel#NORMAL}。</li>
 * </ol>
 * 子类仅需提供维度、引擎名称及具体规则特征元数据即可。
 * </p>
 */
@Slf4j
public abstract class AbstractRuleAssetAuditEngine implements AuditEngine {

    /**
     * 对多媒体文件资产执行通用规则安全合规审查。
     *
     * @param assetId 资产标识（文件 ID 或路径）
     * @return 审查判定明细结果
     */
    protected EngineAuditResult auditAsset(String assetId) {
        // 步骤 1：基础参数校验，文件资产缺失则直接判定严重违规阻断
        if (assetId == null || assetId.isBlank()) {
            return EngineAuditResult.of(
                    getDimension(),
                    getEngineType(),
                    ReviewLevel.ILLEGAL,
                    BigDecimal.valueOf(100.00),
                    List.of(getMissingTag()),
                    getMissingReason()
            );
        }

        String lower = assetId.toLowerCase();

        // 步骤 2：测试桩与严重违禁特征检测
        List<String> illegalKeywords = getIllegalKeywords();
        if (illegalKeywords != null) {
            for (String keyword : illegalKeywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return EngineAuditResult.of(
                            getDimension(),
                            getEngineType(),
                            ReviewLevel.ILLEGAL,
                            getIllegalConfidence(),
                            List.of(getIllegalTag()),
                            getIllegalReason()
                    );
                }
            }
        }

        // 步骤 3：疑似敏感特征检测，建议转人工复审
        List<String> suspiciousKeywords = getSuspiciousKeywords();
        if (suspiciousKeywords != null) {
            for (String keyword : suspiciousKeywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return EngineAuditResult.of(
                            getDimension(),
                            getEngineType(),
                            ReviewLevel.SUSPICIOUS,
                            getSuspiciousConfidence(),
                            List.of(getSuspiciousTag()),
                            getSuspiciousReason()
                    );
                }
            }
        }

        // 步骤 4：通过常规检测，返回合规判定
        return EngineAuditResult.normal(getDimension(), getEngineType(), getNormalReason());
    }

    /** 获取资产缺失时的明细标签标识。 */
    protected abstract String getMissingTag();

    /** 获取资产缺失时的文字原因说明。 */
    protected abstract String getMissingReason();

    /** 获取严重违规特征匹配关键字列表。 */
    protected abstract List<String> getIllegalKeywords();

    /** 获取严重违规时的明细标签标识。 */
    protected abstract String getIllegalTag();

    /** 获取严重违规时的文字原因说明。 */
    protected abstract String getIllegalReason();

    /** 获取严重违规时的判定置信度（默认 98.50）。 */
    protected BigDecimal getIllegalConfidence() {
        return BigDecimal.valueOf(98.50);
    }

    /** 获取疑似敏感特征匹配关键字列表。 */
    protected abstract List<String> getSuspiciousKeywords();

    /** 获取疑似敏感时的明细标签标识。 */
    protected abstract String getSuspiciousTag();

    /** 获取疑似敏感时的文字原因说明。 */
    protected abstract String getSuspiciousReason();

    /** 获取疑似敏感时的判定置信度（默认 70.00）。 */
    protected BigDecimal getSuspiciousConfidence() {
        return BigDecimal.valueOf(70.00);
    }

    /** 获取合规放行时的文字原因说明。 */
    protected abstract String getNormalReason();
}
