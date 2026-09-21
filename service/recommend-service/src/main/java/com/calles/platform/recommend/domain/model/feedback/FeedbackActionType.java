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
    DISLIKE("DISLIKE", "主动负反馈");

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
