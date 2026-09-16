package com.calles.platform.audit.domain.model.enums;

import lombok.Getter;

/**
 * 平台通用可用性状态枚举。
 */
@Getter
public enum CommonStatus {

    /** 正常生效。 */
    ACTIVE("ACTIVE", "正常"),

    /** 违规下线或停用。 */
    DISABLED("DISABLED", "停用");

    private final String code;
    private final String description;

    CommonStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static CommonStatus fromCode(String code) {
        for (CommonStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的可用状态编码: " + code);
    }
}
