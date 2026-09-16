package com.calles.platform.audit.domain.model.enums;

import lombok.Getter;

/**
 * 审核风险研判级别枚举。
 *
 * 用于机器模型判定打分与聚合仲裁，遵循安全优先策略：ILLEGAL > SUSPICIOUS > NORMAL。
 */
@Getter
public enum ReviewLevel {

    /** 正常无违规风险，准许自动放行。 */
    NORMAL("NORMAL", "合规正常", 0),

    /** 疑似可疑内容，需转入人工复审工单池。 */
    SUSPICIOUS("SUSPICIOUS", "疑似可疑", 1),

    /** 严重违规违禁，直接阻断驳回。 */
    ILLEGAL("ILLEGAL", "违规阻断", 2);

    private final String code;
    private final String description;
    private final int severity;

    ReviewLevel(String code, String description, int severity) {
        this.code = code;
        this.description = description;
        this.severity = severity;
    }

    public static ReviewLevel fromCode(String code) {
        for (ReviewLevel level : values()) {
            if (level.code.equalsIgnoreCase(code)) {
                return level;
            }
        }
        throw new IllegalArgumentException("未知的风险级别编码: " + code);
    }
}
