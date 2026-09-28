package com.calles.platform.recommend.domain.model.feedback;

import lombok.Getter;

/**
 * 用户交互行为反馈动作类型枚举。
 *
 * <p>用于区分客户端上报的行为事实性质，支持正向消费、消极跳过与主动负反馈。</p>
 */
@Getter
public enum FeedbackActionType {

    /** 客户端有效曝光 (进入视口展示)。 */
    IMPRESSION("IMPRESSION", "有效曝光"),

    /** 客户端实际播放消费 (完播或有效观看)。 */
    PLAY("PLAY", "播放消费"),

    /** 快速滑过或跳过 (未产生有效消费)。 */
    SKIP("SKIP", "滑过跳过"),

    /** 用户主动点击不感兴趣或减少此类推荐。 */
    DISLIKE("DISLIKE", "主动负反馈"),

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

    /**
     * 判断当前行为是否允许通过客户端 HTTP 接口直接上报。
     *
     * <p>仅视口曝光 (IMPRESSION)、播放消费 (PLAY)、滑过跳过 (SKIP) 和主动负反馈 (DISLIKE) 允许由客户端直接上报；
     * 其余点赞、收藏、分享、完播等必须由服务端互动模块经过核验后通过领域事件异步接入。</p>
     *
     * @return true 若允许客户端直接上报；false 若仅限服务端核验事件
     */
    public boolean isClientReportable() {
        return this == IMPRESSION || this == PLAY || this == SKIP || this == DISLIKE;
    }
}
