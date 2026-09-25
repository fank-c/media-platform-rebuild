package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户观看历史与断点持久化对象 (PO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_watch_history")
public class WatchHistoryPO {

    /** 主键 UUID。 */
    @TableId("id")
    private String id;

    /** 用户账号 ID。 */
    @TableField("user_id")
    private String userId;

    /** 视频公开短码。 */
    @TableField("vid")
    private String vid;

    /** 上次播放头断点位置 (秒)。 */
    @TableField("last_position")
    private Integer lastPosition;

    /** 累计观看时长 (秒)。 */
    @TableField("watched_duration")
    private Integer watchedDuration;

    /** 当前观看会话累计有效观看时长 (秒)。 */
    @TableField("session_watched_duration")
    private Integer sessionWatchedDuration;

    /** 当前会话是否已经发送播放事件：1=已发送, 0=未发送。 */
    @TableField("session_play_emitted")
    private Integer sessionPlayEmitted;

    /** 上一会话达到 30% 门槛从而允许下一次会话触发播放事件：1=具备资格, 0=不具备。 */
    @TableField("eligible_for_next_play")
    private Integer eligibleForNextPlay;

    /** 视频总时长 (秒)。 */
    @TableField("video_duration")
    private Integer videoDuration;

    /** 是否完播：1=完播, 0=未完播。 */
    @TableField("completed")
    private Integer completed;

    /** 首次观看时间。 */
    @TableField("first_watch_at")
    private LocalDateTime firstWatchAt;

    /** 最近心跳活跃时间。 */
    @TableField("last_watch_at")
    private LocalDateTime lastWatchAt;

    /** 最近一次计入有效播放并生成播放事件的时间。 */
    @TableField("last_valid_play_at")
    private LocalDateTime lastValidPlayAt;

    /** 逻辑删除标记：0=正常, 1=已删除。 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    public WatchHistory toDomain() {
        return WatchHistory.builder()
                .id(this.id)
                .userId(this.userId)
                .vid(this.vid)
                .lastPosition(this.lastPosition != null ? this.lastPosition : 0)
                .watchedDuration(this.watchedDuration != null ? this.watchedDuration : 0)
                .sessionWatchedDuration(this.sessionWatchedDuration != null ? this.sessionWatchedDuration : 0)
                .sessionPlayEmitted(this.sessionPlayEmitted != null && this.sessionPlayEmitted == 1)
                .eligibleForNextPlay(this.eligibleForNextPlay != null && this.eligibleForNextPlay == 1)
                .videoDuration(this.videoDuration != null ? this.videoDuration : 0)
                .completed(this.completed != null && this.completed == 1)
                .firstWatchAt(this.firstWatchAt)
                .lastWatchAt(this.lastWatchAt)
                .lastValidPlayAt(this.lastValidPlayAt)
                .deleted(this.deleted != null && this.deleted == 1)
                .build();
    }

    public static WatchHistoryPO fromDomain(WatchHistory domain) {
        if (domain == null) {
            return null;
        }
        return WatchHistoryPO.builder()
                .id(domain.getId())
                .userId(domain.getUserId())
                .vid(domain.getVid())
                .lastPosition(domain.getLastPosition())
                .watchedDuration(domain.getWatchedDuration())
                .sessionWatchedDuration(domain.getSessionWatchedDuration())
                .sessionPlayEmitted(domain.isSessionPlayEmitted() ? 1 : 0)
                .eligibleForNextPlay(domain.isEligibleForNextPlay() ? 1 : 0)
                .videoDuration(domain.getVideoDuration())
                .completed(domain.isCompleted() ? 1 : 0)
                .firstWatchAt(domain.getFirstWatchAt())
                .lastWatchAt(domain.getLastWatchAt())
                .lastValidPlayAt(domain.getLastValidPlayAt())
                .deleted(domain.isDeleted() ? 1 : 0)
                .build();
    }
}
