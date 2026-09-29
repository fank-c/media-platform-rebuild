package com.calles.platform.recommend.application.client.dto;

import java.util.List;

/**
 * 批量查询公开视频统计的请求契约。
 *
 * @param vids 视频公开短码列表
 */
public record FollowingVideoStatsRequest(List<String> vids) {
}
