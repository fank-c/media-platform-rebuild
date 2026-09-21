package com.calles.platform.recommend.interfaces.web.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 首页推荐瀑布流网络响应体。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RecommendFeedResponse {

    /** 推荐项列表。 */
    private List<RecommendItemDTO> items;

    /** 是否有更多内容。 */
    private Boolean hasMore;
}
