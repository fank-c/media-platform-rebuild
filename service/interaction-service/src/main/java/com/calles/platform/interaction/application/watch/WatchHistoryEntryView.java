package com.calles.platform.interaction.application.watch;

import com.calles.platform.interaction.domain.model.watch.WatchHistoryEntry;
import java.time.LocalDateTime;

/**
 * 观看历史条目对外视图。
 *
 * <p>职责边界：与 {@link WatchHistoryEntry} 领域投影一一对应，作为应用层对表现层暴露的稳定契约，
 * 避免表现层直接依赖新模型表结构。</p>
 *
 * @param id 记录主键
 * @param vid 视频公开业务短码
 * @param lastPosition 断点播放位置 (秒)
 * @param watchedDuration 累计有效观看时长 (秒)
 * @param videoDuration 视频时长 (秒)
 * @param completed 是否曾经完播
 * @param firstWatchAt 首次观看时间
 * @param lastWatchAt 最近一次观看时间
 */
public record WatchHistoryEntryView(
        String id,
        String vid,
        int lastPosition,
        int watchedDuration,
        int videoDuration,
        boolean completed,
        LocalDateTime firstWatchAt,
        LocalDateTime lastWatchAt
) {

    /**
     * 由领域投影构造对外视图。
     *
     * @param entry 观看历史领域投影
     * @return 对外视图
     */
    public static WatchHistoryEntryView from(WatchHistoryEntry entry) {
        return new WatchHistoryEntryView(
                entry.id(),
                entry.vid(),
                entry.lastPosition(),
                entry.watchedDuration(),
                entry.videoDuration(),
                entry.completed(),
                entry.firstWatchAt(),
                entry.lastWatchAt());
    }
}
