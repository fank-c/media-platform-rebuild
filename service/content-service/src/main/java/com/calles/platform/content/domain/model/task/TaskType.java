package com.calles.platform.content.domain.model.task;

import lombok.Getter;

/**
 * 视频异步流水线子任务类型枚举。
 *
 * <p>涵盖内容合规机审/人审、多画质切片转码以及多模态特征向量提取。</p>
 */
@Getter
public enum TaskType {

    /** 内容安全合规风控审核（阻断性硬门禁）。 */
    AUDIT("AUDIT", true, "内容合规审核"),

    /** 标清 720P 基准流转码（基础体验门禁）。 */
    TRANSCODE_720P("TRANSCODE_720P", true, "720P高清转码"),

    /** 高清 1080P 基准流转码（基础体验门禁）。 */
    TRANSCODE_1080P("TRANSCODE_1080P", true, "1080P超清转码"),

    /** 超高清 4K 可选流转码（长耗时异步追加，非阻断性）。 */
    TRANSCODE_4K("TRANSCODE_4K", false, "4K超高清转码"),

    /** 多模态语义特征向量计算（推荐检索准入门禁）。 */
    VECTOR_EMBEDDING("VECTOR_EMBEDDING", true, "向量检索计算");

    /** 任务类型唯一标识编码。 */
    private final String code;

    /** 是否属于快速发布准入的基准门禁任务（true 表示该任务必须 SUCCESS 才能自动触发 PUBLISHED 发布）。 */
    private final boolean baselineGate;

    /** 任务业务中文描述说明。 */
    private final String description;

    /**
     * 枚举构造方法。
     *
     * @param code 任务类型代码
     * @param baselineGate 是否基准门禁
     * @param description 描述信息
     */
    TaskType(String code, boolean baselineGate, String description) {
        this.code = code;
        this.baselineGate = baselineGate;
        this.description = description;
    }

    /**
     * 根据字符串编码解析对应的任务类型枚举，忽略大小写并自动去除首尾空白。
     *
     * @param code 任务类型编码字面量
     * @return 匹配的 {@link TaskType} 枚举实例，输入为 null 时返回 null
     * @throws IllegalArgumentException 当传入未知类型字符串时抛出
     */
    public static TaskType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TaskType type : values()) {
            if (type.getCode().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知任务类型: " + code);
    }
}
