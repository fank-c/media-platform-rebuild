package com.calles.platform.recommend.interfaces.web.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 推荐项网络传输响应 DTO。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RecommendItemDTO {

    /** 视频公开业务短码。 */
    private String vid;

    /** 召回渠道 (VECTOR, COLD_START)。 */
    private String recallChannel;

    /** 推荐综合打分。 */
    private Double score;

    /** 推荐理由 (可选)。 */
    private String reason;
}
