package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.watch.WatchEventClaim;
import java.time.LocalDateTime;import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 观看事件凭据持久化对象 (PO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_watch_event_claim")
public class WatchEventClaimPO {

    /** 凭据主键 UUID。 */
    @TableId("id")
    private String id;

    /** 用户账号 ID。 */
    @TableField("user_id")
    private String userId;

    /** 视频公开短码。 */
    @TableField("vid")
    private String vid;

    /** 所属观看会话 ID。 */
    @TableField("session_id")
    private String sessionId;

    /** 事件类型字面量。 */
    @TableField("event_type")
    private String eventType;

    /** 同事务写入的 Outbox 事件 ID。 */
    @TableField("outbox_event_id")
    private String outboxEventId;

    /** 抢占成功时间。 */
    @TableField("claimed_at")
    private LocalDateTime claimedAt;

    /**
     * 由领域实体构造持久化对象。
     *
     * @param domain 观看事件凭据领域实体
     * @return 持久化对象，入参为空时返回 null
     */
    public static WatchEventClaimPO fromDomain(WatchEventClaim domain) {
        if (domain == null) {
            return null;
        }
        return WatchEventClaimPO.builder()
                .id(domain.getId())
                .userId(domain.getUserId())
                .vid(domain.getVid())
                .sessionId(domain.getSessionId())
                .eventType(domain.getEventType() != null ? domain.getEventType().name() : null)
                .outboxEventId(domain.getOutboxEventId())
                .claimedAt(domain.getClaimedAt())
                .build();
    }
}
