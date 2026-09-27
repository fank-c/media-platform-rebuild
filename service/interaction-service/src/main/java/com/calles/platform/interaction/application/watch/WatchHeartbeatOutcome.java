package com.calles.platform.interaction.application.watch;

/**
 * 观看心跳处理结果。
 *
 * <p>该结果同时服务于 v1 兼容响应与 v2 响应；两个入口只做协议映射，业务判定完全一致。</p>
 *
 * @param vid 视频公开业务短码
 * @param sessionId 服务端当前活跃会话 ID
 * @param acceptedSequence 服务端已接受的最大心跳序号，客户端未提供序号时为空
 * @param lastPosition 最近一次播放位置 (秒)
 * @param watchedDuration 用户在该视频上的累计有效观看时长 (秒)
 * @param sessionWatchedDuration 本会话服务端认可的有效观看时长 (秒)
 * @param videoDuration 本会话使用的视频时长快照 (秒)，0 表示无可用快照
 * @param qualificationThreshold 本会话合格观看事件门槛 (秒)，0 表示无可用快照
 * @param qualifiedThisSession 本会话是否已达到合格观看事件门槛
 * @param viewCountedThisSession 本会话是否已计入公开播放量 (起播判定结果)
 * @param completedThisSession 本会话是否已计入完播
 * @param duplicateOrStaleRequest 本次请求是否为重复投递或乱序到达
 */
public record WatchHeartbeatOutcome(
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
        boolean duplicateOrStaleRequest
) {
}
