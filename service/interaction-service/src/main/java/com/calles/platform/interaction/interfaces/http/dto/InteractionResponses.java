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
     * 播放心跳响应。
     *
     * @param vid 视频公开业务短码
     * @param sessionId 服务端当前活跃会话 ID，客户端应在后续心跳回传
     * @param acceptedSequence 服务端已接受的最大心跳序号
     * @param lastPosition 最新断点位置 (秒)
     * @param watchedDuration 该视频累计有效观看时长 (秒)
     * @param sessionWatchedDuration 本次会话服务端认可的有效观看时长 (秒)
     * @param videoDuration 服务端本地视频时长快照 (秒)，0 表示暂无可用快照
     * @param qualificationThreshold 本次会话合格观看事件门槛 (秒)
     * @param qualifiedThisSession 本次会话是否已达合格观看事件门槛
     * @param viewCountedThisSession 本次会话是否已计入公开播放量
     * @param completedThisSession 本次会话是否已计入完播
     * @param duplicateRequest 本次请求是否为重复投递或乱序到达
     */
    public record WatchHeartbeat(
            String vid,
            String sessionId,
            Long acceptedSequence,
            int lastPosition,
            int watchedDuration,
            int sessionWatchedDuration,
            int videoDuration,
            int qualificationThreshold,
            boolean qualifiedThisSession,
            boolean viewCountedThisSession,
            boolean completedThisSession,
            boolean duplicateRequest
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
