package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.watch.WatchSession;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 观看会话持久化对象 (PO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_watch_session")
public class WatchSessionPO {

    /** 会话主键 UUID。 */
    @TableId("session_id")
    private String sessionId;

    /** 用户账号 ID。 */
    @TableField("user_id")
    private String userId;

    /** 视频公开短码。 */
    @TableField("vid")
    private String vid;

    /** 会话创建时的视频时长快照 (秒)。 */
    @TableField("duration_snapshot")
    private Integer durationSnapshot;

    /** 会话创建时固定的播放量门槛 (秒)。 */
    @TableField("qualification_threshold")
    private Integer qualificationThreshold;

    /** 本会话累计有效观看时长 (秒)。 */
    @TableField("credited_duration")
    private Integer creditedDuration;

    /** 已处理的最大心跳序号。 */
    @TableField("last_sequence")
    private Long lastSequence;

    /** 本会话最近一次播放位置 (秒)。 */
    @TableField("last_position")
    private Integer lastPosition;

    /** 是否达到合格观看事件门槛：1=是, 0=否。 */
    @TableField("qualified")
    private Integer qualified;

    /** 播放量入账时间，非空表示该会话已产生播放量。 */
    @TableField("view_counted_at")
    private LocalDateTime viewCountedAt;

    /** 起播请求幂等键。 */
    @TableField("start_request_key")
    private String startRequestKey;

    /** 会话开始时间。 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** 最近一次有效心跳时间。 */
    @TableField("last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    /** 会话关闭时间。 */
    @TableField("closed_at")
    private LocalDateTime closedAt;

    /**
     * 转换为领域实体。
     *
     * @return 观看会话领域实体
     */
    public WatchSession toDomain() {
        return WatchSession.builder()
                .sessionId(this.sessionId)
                .userId(this.userId)
                .vid(this.vid)
                .durationSnapshot(this.durationSnapshot != null ? this.durationSnapshot : 0)
                .qualificationThreshold(this.qualificationThreshold != null ? this.qualificationThreshold : 0)
                .creditedDuration(this.creditedDuration != null ? this.creditedDuration : 0)
                .lastSequence(this.lastSequence)
                .lastPosition(this.lastPosition != null ? this.lastPosition : 0)
                .qualified(this.qualified != null && this.qualified == 1)
                .viewCountedAt(this.viewCountedAt)
                .startRequestKey(this.startRequestKey)
                .startedAt(this.startedAt)
                .lastHeartbeatAt(this.lastHeartbeatAt)
                .closedAt(this.closedAt)
                .build();
    }

    /**
     * 由领域实体构造持久化对象。
     *
     * @param domain 观看会话领域实体
     * @return 持久化对象，入参为空时返回 null
     */
    public static WatchSessionPO fromDomain(WatchSession domain) {
        if (domain == null) {
            return null;
        }
        return WatchSessionPO.builder()
                .sessionId(domain.getSessionId())
                .userId(domain.getUserId())
                .vid(domain.getVid())
                .durationSnapshot(domain.getDurationSnapshot())
                .qualificationThreshold(domain.getQualificationThreshold())
                .creditedDuration(domain.getCreditedDuration())
                .lastSequence(domain.getLastSequence())
                .lastPosition(domain.getLastPosition())
                .qualified(domain.isQualified() ? 1 : 0)
                .viewCountedAt(domain.getViewCountedAt())
                .startRequestKey(domain.getStartRequestKey())
                .startedAt(domain.getStartedAt())
                .lastHeartbeatAt(domain.getLastHeartbeatAt())
                .closedAt(domain.getClosedAt())
                .build();
    }
}
