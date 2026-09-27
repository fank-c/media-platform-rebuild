package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.watch.WatchProgress;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 观看进度与断点持久化对象 (PO)。
 *
 * <p>注意：{@code deleted} 字段刻意不声明为 MyBatis-Plus 逻辑删除字段。
 * 心跳链路必须始终读取物理行，否则删除历史会连同播放量冷却状态一起被隐藏，
 * 出现"删掉历史再重新观看即可重复计数"的漏洞。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_watch_progress")
public class WatchProgressPO {

    /** 记录主键 UUID。 */
    @TableId("id")
    private String id;

    /** 用户账号 ID。 */
    @TableField("user_id")
    private String userId;

    /** 视频公开短码。 */
    @TableField("vid")
    private String vid;

    /** 当前活跃会话 ID。 */
    @TableField("active_session_id")
    private String activeSessionId;

    /** 上次播放头断点位置 (秒)。 */
    @TableField("last_position")
    private Integer lastPosition;

    /** 累计有效观看总时长 (秒)。 */
    @TableField("watched_duration")
    private Integer watchedDuration;

    /** 首次观看时间。 */
    @TableField("first_watch_at")
    private LocalDateTime firstWatchAt;

    /** 最近一次心跳活跃时间。 */
    @TableField("last_watch_at")
    private LocalDateTime lastWatchAt;

    /** 最近一次成功计入播放量的时间。 */
    @TableField("last_view_claimed_at")
    private LocalDateTime lastViewClaimedAt;

    /** 是否已隐藏历史展示：1=隐藏, 0=展示。 */
    @TableField("deleted")
    private Integer deleted;

    /**
     * 转换为领域实体。
     *
     * @return 观看进度领域实体
     */
    public WatchProgress toDomain() {
        return WatchProgress.builder()
                .id(this.id)
                .userId(this.userId)
                .vid(this.vid)
                .activeSessionId(this.activeSessionId)
                .lastPosition(this.lastPosition != null ? this.lastPosition : 0)
                .watchedDuration(this.watchedDuration != null ? this.watchedDuration : 0)
                .firstWatchAt(this.firstWatchAt)
                .lastWatchAt(this.lastWatchAt)
                .lastViewClaimedAt(this.lastViewClaimedAt)
                .deleted(this.deleted != null && this.deleted == 1)
                .build();
    }

    /**
     * 由领域实体构造持久化对象。
     *
     * @param domain 观看进度领域实体
     * @return 持久化对象，入参为空时返回 null
     */
    public static WatchProgressPO fromDomain(WatchProgress domain) {
        if (domain == null) {
            return null;
        }
        return WatchProgressPO.builder()
                .id(domain.getId())
                .userId(domain.getUserId())
                .vid(domain.getVid())
                .activeSessionId(domain.getActiveSessionId())
                .lastPosition(domain.getLastPosition())
                .watchedDuration(domain.getWatchedDuration())
                .firstWatchAt(domain.getFirstWatchAt())
                .lastWatchAt(domain.getLastWatchAt())
                .lastViewClaimedAt(domain.getLastViewClaimedAt())
                .deleted(domain.isDeleted() ? 1 : 0)
                .build();
    }
}
