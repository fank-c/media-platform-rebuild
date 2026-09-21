package com.calles.platform.recommend.application.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 推荐单项应用层计算结果。
 *
 * <p>封装推荐物料公开短码、排序综合打分、召回渠道与推荐理由。</p>
 */
@Getter
public class RecommendItemResult {

    /** 视频公开业务短码。 */
    private final String vid;

    /** 排序综合得分。 */
    private final double score;

    /** 召回渠道标识 (如 VECTOR, COLD_START)。 */
    private final String channel;

    /** 推荐理由说明 (如 "因为你关注了 Java"、"优质推荐")。 */
    private final String reason;

    @JsonCreator
    public RecommendItemResult(
            @JsonProperty("vid") String vid,
            @JsonProperty("score") double score,
            @JsonProperty("channel") String channel,
            @JsonProperty("reason") String reason) {
        this.vid = vid;
        this.score = score;
        this.channel = channel;
        this.reason = reason;
    }
}
