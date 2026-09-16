package com.calles.platform.audit.domain.model.enums;

import lombok.Getter;

/**
 * 审核审查维度枚举。
 */
@Getter
public enum AuditDimension {

    /** 文本信息（标题、简介、标签）。 */
    TEXT("TEXT", "文本元数据"),

    /** 封面图片。 */
    IMAGE("IMAGE", "封面图片"),

    /** 音视频流资产。 */
    VIDEO("VIDEO", "音视频资产");

    private final String code;
    private final String description;

    AuditDimension(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static AuditDimension fromCode(String code) {
        for (AuditDimension dim : values()) {
            if (dim.code.equalsIgnoreCase(code)) {
                return dim;
            }
        }
        throw new IllegalArgumentException("未知的审查维度编码: " + code);
    }
}
