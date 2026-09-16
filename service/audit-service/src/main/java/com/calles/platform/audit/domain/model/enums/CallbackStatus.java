package com.calles.platform.audit.domain.model.enums;

import lombok.Getter;

/**
 * 审核结果回调下游内容服务状态枚举。
 */
@Getter
public enum CallbackStatus {

    /** 待回调。 */
    PENDING("PENDING", "待回调"),

    /** 回调成功。 */
    SUCCESS("SUCCESS", "回调成功"),

    /** 回调失败，需调度重试。 */
    FAILED("FAILED", "回调失败");

    private final String code;
    private final String description;

    CallbackStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static CallbackStatus fromCode(String code) {
        for (CallbackStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的回调状态编码: " + code);
    }
}
