package com.calles.platform.interaction.domain.model.counter;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频公开互动统计的计数维度类型枚举。
 *
 * <p>定义视频互动公开计数的四类维度：有效播放量、点赞数、收藏数、分享数，
 * 并提供与底层数据库列名及枚举编码的双向映射与安全校验。</p>
 */
@Getter
@RequiredArgsConstructor
public enum CounterType {

    /** 累计有效播放次数。 */
    VIEW("VIEW", "view_count"),

    /** 累计有效点赞数。 */
    LIKE("LIKE", "like_count"),

    /** 累计有效收藏数。 */
    STAR("STAR", "star_count"),

    /** 累计有效分享数。 */
    SHARE("SHARE", "share_count");

    /** 业务编码（大写字符）。 */
    private final String code;

    /** 对应 interaction_video_counter 数据库列名。 */
    private final String columnName;

    /**
     * 根据编码解析计数类型枚举。
     *
     * @param code 编码字符串（如 "VIEW"）
     * @return 对应的枚举实例
     * @throws IllegalArgumentException 当编码为空或未定义时抛出
     */
    public static CounterType fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("计数类型编码不能为空");
        }
        for (CounterType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的计数类型编码: " + code);
    }
}
