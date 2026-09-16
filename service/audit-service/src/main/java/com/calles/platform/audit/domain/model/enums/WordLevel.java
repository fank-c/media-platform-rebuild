package com.calles.platform.audit.domain.model.enums;

import lombok.Getter;

/**
 * 敏感词拦截级别枚举。
 */
@Getter
public enum WordLevel {

    /** 直接拦截驳回。 */
    ILLEGAL("ILLEGAL", "直接阻断"),

    /** 疑似违规，触发人工审核。 */
    SUSPICIOUS("SUSPICIOUS", "疑似转人审");

    private final String code;
    private final String description;

    WordLevel(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static WordLevel fromCode(String code) {
        for (WordLevel level : values()) {
            if (level.code.equalsIgnoreCase(code)) {
                return level;
            }
        }
        throw new IllegalArgumentException("未知的敏感词级别编码: " + code);
    }
}
