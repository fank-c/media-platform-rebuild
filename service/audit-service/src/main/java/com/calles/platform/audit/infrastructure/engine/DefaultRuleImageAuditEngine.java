package com.calles.platform.audit.infrastructure.engine;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.engine.ImageAuditEngine;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 默认多媒体封面规则审查引擎（支持测试桩模拟与可插拔第三方云检测扩展）。
 *
 * <p>职责：对视频封面图片资产的有效性、违禁特征进行前置规则检测。</p>
 */
@Slf4j
@Component
public class DefaultRuleImageAuditEngine implements ImageAuditEngine {

    /** 引擎标识名称。 */
    private static final String ENGINE_NAME = "RULE_IMAGE";

    /**
     * 对封面图片资产执行安全合规审查。
     *
     * @param coverFileId 封面文件资产 ID
     * @return 审查判定明细结果
     */
    @Override
    public EngineAuditResult auditCover(String coverFileId) {
        // 步骤 1：基础参数校验，封面文件缺失则判定为严重违规阻断
        if (coverFileId == null || coverFileId.isBlank()) {
            return EngineAuditResult.of(
                    AuditDimension.IMAGE,
                    ENGINE_NAME,
                    ReviewLevel.ILLEGAL,
                    BigDecimal.valueOf(100.00),
                    List.of("MISSING_COVER"),
                    "视频缺少封面图资产，审查不通过"
            );
        }

        // 步骤 2：测试与安全规则模拟桩检测（便于自动化测试及无外网环境下的风控演练）
        String lower = coverFileId.toLowerCase();
        if (lower.contains("illegal") || lower.contains("porn") || lower.contains("violation")) {
            return EngineAuditResult.of(
                    AuditDimension.IMAGE,
                    ENGINE_NAME,
                    ReviewLevel.ILLEGAL,
                    BigDecimal.valueOf(98.50),
                    List.of("IMAGE_PORN_OR_VIOLATION"),
                    "封面图片命中严重违规特征库 (测试桩检测)"
            );
        }

        // 步骤 3：疑似敏感特征检测，转人工审核
        if (lower.contains("suspicious")) {
            return EngineAuditResult.of(
                    AuditDimension.IMAGE,
                    ENGINE_NAME,
                    ReviewLevel.SUSPICIOUS,
                    BigDecimal.valueOf(70.00),
                    List.of("IMAGE_SUSPICIOUS"),
                    "封面图片包含疑似低俗或敏感元素，转人工审核"
            );
        }

        // 步骤 4：通过常规检测，返回合规判定
        return EngineAuditResult.normal(AuditDimension.IMAGE, ENGINE_NAME, "封面图片合规正常");
    }
}
