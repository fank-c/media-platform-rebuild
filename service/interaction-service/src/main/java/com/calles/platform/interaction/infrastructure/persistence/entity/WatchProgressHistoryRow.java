package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.calles.platform.interaction.domain.model.watch.WatchHistoryEntry;
import java.time.LocalDateTime;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 观看历史列表查询投影行。
 *
 * <p>该对象是 {@code interaction_watch_progress} 与 {@code interaction_video_snapshot}
 * 两个自属表的投影结果，只用于只读查询，不参与任何写入。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatchProgressHistoryRow {

    /** 进度记录主键。 */
    private String id;

    /** 视频公开短码。 */
    private String vid;

    /** 断点播放位置 (秒)。 */
    private Integer lastPosition;

    /** 累计有效观看时长 (秒)。 */
    private Integer watchedDuration;

    /** 视频时长快照 (秒)。 */
    private Integer videoDuration;

    /** 首次观看时间。 */
    private LocalDateTime firstWatchAt;

    /** 最近一次观看时间。 */
    private LocalDateTime lastWatchAt;

    /**
     * 判断该行对应视频是否曾经完播。
     *
     * @param completedVids 已完播视频短码集合
     * @return true 表示存在完播凭据
     */
    public boolean isCompleted(Set<String> completedVids) {
        return completedVids != null && this.vid != null && completedVids.contains(this.vid);
    }

    /**
     * 转换为领域展示投影。
     *
     * @param completedVids 已完播视频短码集合
     * @return 观看历史展示条目
     */
    public WatchHistoryEntry toEntry(Set<String> completedVids) {
        return new WatchHistoryEntry(
                this.id,
                this.vid,
                this.lastPosition != null ? this.lastPosition : 0,
                this.watchedDuration != null ? this.watchedDuration : 0,
                this.videoDuration != null ? this.videoDuration : 0,
                isCompleted(completedVids),
                this.firstWatchAt,
                this.lastWatchAt
        );
    }
}
