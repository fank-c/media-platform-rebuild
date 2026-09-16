package com.calles.platform.audit.application.executor.model;

import lombok.Getter;

/**
 * 审核业务归属类型枚举 (AuditBizType)。
 *
 * <p>定义机审策略流水线支持的业务形态，消除散落的魔法字符串并提供编译期类型安全保障。</p>
 */
@Getter
public enum AuditBizType {

    /** 视频业务（包含视频标题、简介、封面多媒体及主视频文件资产）。 */
    VIDEO("VIDEO", "视频业务"),

    /** 互动评论业务（预留：用于评论文本及贴图标注合规检测）。 */
    COMMENT("COMMENT", "互动评论"),

    /** 用户资料业务（预留：用于用户头像、背景图合规审查）。 */
    AVATAR("AVATAR", "用户头像");

    /** 业务英文标识编码 (如 VIDEO)。 */
    private final String code;

    /** 业务中文描述说明。 */
    private final String description;

    AuditBizType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 宽容解析业务类型编码（忽略大小写与首尾空格）。
     *
     * @param code 业务编码字符串（如 "VIDEO", "video"）
     * @return 匹配的业务类型枚举；若为空或未匹配则返回 null
     */
    public static AuditBizType of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AuditBizType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        return null;
    }

    /**
     * 判断当前枚举是否与指定的编码匹配（忽略大小写与首尾空格）。
     *
     * @param code 业务编码字符串
     * @return true 表示匹配
     */
    public boolean matches(String code) {
        return code != null && this.code.equalsIgnoreCase(code.trim());
    }
}
