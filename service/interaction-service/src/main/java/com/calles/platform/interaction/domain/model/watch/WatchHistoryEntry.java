package com.calles.platform.interaction.domain.model.watch;

import java.time.LocalDateTime;

/**
 * 观看历史展示条目。
 *
 * <p>职责边界：只承载历史列表展示所需字段，是进度表、视频时长快照与完播凭据的只读投影，
 * 不包含任何防刷或资格状态。</p>
 *
 * @param id 进度记录主键
 * @param vid 视频公开业务短码
 * @param lastPosition 断点播放位置 (秒)
 * @param watchedDuration 累计有效观看时长 (秒)
 * @param videoDuration 视频时长快照 (秒)，0 表示暂无快照
 * @param completed 该视频是否存在完播凭据（历史语义为"曾经完播过"）
 * @param firstWatchAt 首次观看时间
 * @param lastWatchAt 最近一次观看时间
 */
public record WatchHistoryEntry(
        String id,
        String vid,
        int lastPosition,
        int watchedDuration,
        int videoDuration,
        boolean completed,
        LocalDateTime firstWatchAt,
        LocalDateTime lastWatchAt
) {
}
