package com.calles.platform.recommend.interfaces.web.dto;

import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import lombok.Getter;

/**
 * 客户端允许直接上报的行为动作枚举。
 *
 * <p>该枚举严格界定 HTTP 反馈入口的可信输入边界，仅允许客户端上报原生视口与手势行为（如曝光、播放、滑过、负反馈），
 * 杜绝客户端伪造点赞、收藏或经服务端核验的完播等互动事件。</p>
 */
@Getter
public enum ClientFeedbackAction {

    /** 客户端有效曝光 (进入视口展示)。 */
    IMPRESSION(FeedbackActionType.IMPRESSION, "有效曝光"),

    /** 客户端实际播放消费 (主动播放)。 */
    PLAY(FeedbackActionType.PLAY, "播放消费"),

    /** 快速滑过或跳过 (未产生有效消费)。 */
    SKIP(FeedbackActionType.SKIP, "滑过跳过"),

    /** 用户主动点击不感兴趣或减少此类推荐。 */
    DISLIKE(FeedbackActionType.DISLIKE, "主动负反馈");

    /** 映射的推荐领域行为类型。 */
    private final FeedbackActionType domainType;

    /** 业务说明。 */
    private final String description;

    ClientFeedbackAction(FeedbackActionType domainType, String description) {
        this.domainType = domainType;
        this.description = description;
    }

    /**
     * 转换为推荐领域层的行为类型。
     *
     * @return 对应的 FeedbackActionType 领域枚举实例
     */
    public FeedbackActionType toDomainType() {
        return domainType;
    }
}
