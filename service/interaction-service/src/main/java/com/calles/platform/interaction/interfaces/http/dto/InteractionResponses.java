package com.calles.platform.interaction.interfaces.http.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 互动服务对外 HTTP 响应 DTO 汇总。
 */
public final class InteractionResponses {

    private InteractionResponses() {
    }

    /**
     * 单视频公开互动统计数据响应。
     */
    public record VideoStat(
            String vid,
            long viewCount,
            long likeCount,
            long starCount,
            long shareCount,
            long commentCount
    ) { }

    /**
     * 当前登录用户在视频上的互动状态快照响应。
     */
    public record MyState(
            String vid,
            boolean liked,
            boolean starred,
            int lastWatchPosition,
            boolean completed
    ) { }

    /**
     * 播放断点进度响应。
     */
    public record WatchProgress(
            String vid,
            int lastPosition,
            int watchedDuration,
            int videoDuration,
            boolean completed
    ) { }

    /**
     * 收藏夹信息响应。
     */
    public record StarFolderItem(
            String id,
            String title,
            boolean isDefault,
            int status,
            LocalDateTime createdAt
    ) { }

    /**
     * 收藏视频明细项响应。
     */
    public record StarVideoItem(
            String id,
            String folderId,
            String vid,
            LocalDateTime createdAt
    ) { }

    /**
     * 观看历史记录项响应。
     */
    public record WatchHistoryItem(
            String id,
            String vid,
            int lastPosition,
            int watchedDuration,
            int videoDuration,
            boolean completed,
            LocalDateTime firstWatchAt,
            LocalDateTime lastWatchAt
    ) { }

    /**
     * 通用简单动作结果响应。
     */
    public record ActionResult(
            String vid,
            String action,
            boolean active
    ) { }
}
