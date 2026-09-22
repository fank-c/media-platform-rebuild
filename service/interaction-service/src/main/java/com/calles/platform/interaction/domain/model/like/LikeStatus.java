package com.calles.platform.interaction.domain.model.like;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 视频点赞状态枚举。
 */
@Getter
@RequiredArgsConstructor
public enum LikeStatus {

    /** 已点赞有效状态。 */
    ACTIVE(1),

    /** 已取消点赞状态。 */
    CANCELLED(0);

    private final int value;

    /**
     * 根据整型数值解析状态枚举。
     *
     * @param value 数据库存储状态码 (1 或 0)
     * @return 对应的 LikeStatus 枚举
     * @throws IllegalArgumentException 当传入未知状态值时抛出
     */
    public static LikeStatus fromValue(int value) {
        for (LikeStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的点赞状态值: " + value);
    }
}
