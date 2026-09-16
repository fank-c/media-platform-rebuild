package com.calles.platform.audit.infrastructure.engine;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.engine.VideoAuditEngine;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 默认视频资产规则审查引擎基础设施实现。
 *
 * <p>职责：对主视频流文件资产的存在性、违禁特征标识进行前置合规检测。</p>
 */
@Slf4j
@Component
public class DefaultVideoAuditEngine implements VideoAuditEngine {

    /** 引擎标识名称。 */
    private static final String ENGINE_NAME = "RULE_VIDEO";

    /**
     * 对视频资产执行基础合规性审查。
     *
     * @param videoFileId 视频文件资产 ID
     * @return 审查判定明细结果
     */
    @Override
    public EngineAuditResult auditVideo(String videoFileId) {
        // 步骤 1：基础参数校验，视频文件缺失则直接判定严重违规阻断
        if (videoFileId == null || videoFileId.isBlank()) {
            return EngineAuditResult.of(
                    AuditDimension.VIDEO,
                    ENGINE_NAME,
                    ReviewLevel.ILLEGAL,
                    BigDecimal.valueOf(100.00),
                    List.of("MISSING_VIDEO_FILE"),
                    "缺少主视频文件资产，审查不通过"
            );
        }

        // 步骤 2：测试桩与安全违规特征检测
        String lower = videoFileId.toLowerCase();
        if (lower.contains("illegal") || lower.contains("violation")) {
            return EngineAuditResult.of(
                    AuditDimension.VIDEO,
                    ENGINE_NAME,
                    ReviewLevel.ILLEGAL,
                    BigDecimal.valueOf(99.00),
                    List.of("VIDEO_CONTENT_ILLEGAL"),
                    "视频画面包含严重违规内容 (测试桩检测)"
            );
        }

        // 步骤 3：疑似违规特征检测，转人工审核
        if (lower.contains("suspicious")) {
            return EngineAuditResult.of(
                    AuditDimension.VIDEO,
                    ENGINE_NAME,
                    ReviewLevel.SUSPICIOUS,
                    BigDecimal.valueOf(65.00),
                    List.of("VIDEO_CONTENT_SUSPICIOUS"),
                    "视频画面疑似违规，需人工抽检查验"
            );
        }

        // 步骤 4：通过常规检测，返回合规判定
        return EngineAuditResult.normal(AuditDimension.VIDEO, ENGINE_NAME, "视频多媒体资产基础合规校验通过");
    }
}
