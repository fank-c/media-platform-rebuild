package com.calles.platform.recommend.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 推荐首页瀑布流应用层结果。
 */
@Getter
@AllArgsConstructor
public class RecommendFeedResult {

    /** 推荐项列表。 */
    private final List<RecommendItemResult> items;

    /** 是否有更多内容。 */
    private final boolean hasMore;
}
