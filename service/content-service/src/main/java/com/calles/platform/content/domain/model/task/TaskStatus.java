package com.calles.platform.content.domain.model.task;

import lombok.Getter;

/**
 * 视频异步处理子任务生命周期状态枚举。
 */
@Getter
public enum TaskStatus {

    /** 待调度排队中。 */
    PENDING("PENDING", false),

    /** 正在执行中。 */
    RUNNING("RUNNING", false),

    /** 执行成功完成。 */
    SUCCESS("SUCCESS", true),

    /** 执行失败。 */
    FAILED("FAILED", true),

    /** 已被取消终止。 */
    CANCELED("CANCELED", true);

    /** 任务状态唯一编码标识。 */
    private final String code;

    /** 是否属于终态（SUCCESS, FAILED, CANCELED 均为终态，终态后禁止逆向变更为 PENDING 或 RUNNING）。 */
    private final boolean terminal;

    /**
     * 枚举构造方法。
     *
     * @param code 状态代码
     * @param terminal 是否终态
     */
    TaskStatus(String code, boolean terminal) {
        this.code = code;
        this.terminal = terminal;
    }

    /**
     * 根据字符串编码解析对应的任务状态枚举，忽略大小写并自动去除首尾空白。
     *
     * @param code 任务状态编码字面量
     * @return 匹配的 {@link TaskStatus} 枚举实例，输入为 null 时返回 null
     * @throws IllegalArgumentException 当传入未知状态字符串时抛出
     */
    public static TaskStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TaskStatus status : values()) {
            if (status.getCode().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知任务状态: " + code);
    }
}
