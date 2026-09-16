package com.calles.platform.audit.domain.model.enums;

import lombok.Getter;

/**
 * 审核生命周期阶段枚举。
 *
 * 描述审核任务当前所处的工作流阶段。
 */
@Getter
public enum AuditStage {

    /** 已接收提审事件，排队等待调度。 */
    RECEIVED("RECEIVED", "已接收"),

    /** 自动化机审执行中。 */
    MACHINE_AUDITING("MACHINE_AUDITING", "机审中"),

    /** 机审疑似违规，挂起等待人工审核。 */
    MANUAL_PENDING("MANUAL_PENDING", "待人审"),

    /** 审核已产生终局裁决并归档。 */
    FINISHED("FINISHED", "已完结");

    private final String code;
    private final String description;

    AuditStage(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static AuditStage fromCode(String code) {
        for (AuditStage stage : values()) {
            if (stage.code.equalsIgnoreCase(code)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("未知的审核阶段编码: " + code);
    }
}
