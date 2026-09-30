package com.calles.platform.recommend.domain.model.feedback;

import lombok.Getter;

/**
 * 用户交互行为反馈动作类型枚举。
 *
 * <p>保留 MQ 互动事件的原始语义；客户端不再通过推荐接口上报行为。</p>
 */
@Getter
public enum FeedbackActionType {

    /** 用户点赞。 */
    LIKE("LIKE", "点赞"),

    /** 用户取消点赞，仅记录事实流水，不主动惩罚画像。 */
    UNLIKE("UNLIKE", "取消点赞"),

    /** 用户收藏视频。 */
    STAR("STAR", "收藏"),

    /** 用户取消收藏，仅记录事实流水，不主动惩罚画像。 */
    UNSTAR("UNSTAR", "取消收藏"),

    /** 用户分享视频。 */
    SHARE("SHARE", "分享"),

    /** 服务端校验通过的有效观看资格。 */
    WATCH_VIEW_QUALIFIED("WATCH_VIEW_QUALIFIED", "观看量资格"),

    /** 视频完播事件。 */
    WATCH_COMPLETED("WATCH_COMPLETED", "完播"),

    /** 用户关注作者。 */
    FOLLOW("FOLLOW", "关注作者"),

    /** 用户取消关注作者，仅记录事实流水，不主动惩罚画像。 */
    UNFOLLOW("UNFOLLOW", "取消关注作者");

    /** 动作编码。 */
    private final String code;

    /** 业务描述。 */
    private final String description;

    FeedbackActionType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码安全解析枚举实例。
     *
     * @param code 编码字符串
     * @return 对应的枚举实例
     */
    public static FeedbackActionType fromCode(String code) {
        for (FeedbackActionType type : values()) {
            if (type.getCode().equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的行为类型编码: " + code);
    }
}
