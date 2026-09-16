package com.calles.platform.audit.domain.model.enums;

import lombok.Getter;

/**
 * 审核裁决结论枚举。
 *
 * 描述审核任务的最终判定结果。
 */
@Getter
public enum AuditResult {

    /** 审核尚未产生结论。 */
    PENDING("PENDING", "待定"),

    /** 审核通过，准许发布上线。 */
    PASSED("PASSED", "审核通过"),

    /** 审核违规驳回，打回草稿箱。 */
    REJECTED("REJECTED", "审核驳回");

    private final String code;
    private final String description;

    AuditResult(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static AuditResult fromCode(String code) {
        for (AuditResult result : values()) {
            if (result.code.equalsIgnoreCase(code)) {
                return result;
            }
        }
        throw new IllegalArgumentException("未知的审核结果编码: " + code);
    }
}
