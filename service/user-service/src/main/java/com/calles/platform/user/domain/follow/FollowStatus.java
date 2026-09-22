package com.calles.platform.user.domain.follow;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 关注关系状态枚举，标识用户之间的单向关注生命周期。
 */
@Getter
public enum FollowStatus {

    /** 已取消关注（软状态留存）。 */
    UNFOLLOWED(0, "已取消"),

    /** 正常关注中。 */
    FOLLOWING(1, "已关注");

    /** 数据库存储整数标识。 */
    @EnumValue
    private final int code;

    /** 状态展示描述。 */
    private final String description;

    FollowStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 判断当前状态是否为关注中。
     *
     * @return 若是有效关注返回 true
     */
    public boolean isFollowing() {
        return this == FOLLOWING;
    }

    /**
     * 根据数值代码解析关注状态枚举。
     *
     * @param code 数据库数值代码
     * @return 对应关注状态枚举，未匹配返回 UNFOLLOWED
     */
    public static FollowStatus fromCode(Integer code) {
        if (code == null) {
            return UNFOLLOWED;
        }
        for (FollowStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return UNFOLLOWED;
    }

    @JsonValue
    public int getCode() {
        return code;
    }
}
