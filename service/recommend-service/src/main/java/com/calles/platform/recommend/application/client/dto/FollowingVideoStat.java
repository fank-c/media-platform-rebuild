package com.calles.platform.recommend.application.client.dto;

/**
 * 互动服务返回的公开视频统计最小契约。
 *
 * @param vid 视频公开短码
 * @param viewCount 累计有效播放次数
 */
public record FollowingVideoStat(String vid, Long viewCount) {
}
