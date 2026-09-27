package com.calles.platform.interaction.domain.model.watch;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 观看事件凭据领域实体。
 *
 * <p>职责边界：作为播放量与完播事件的最终防重依据。
 * 数据库唯一键 {@code (user_id, vid, session_id, event_type)} 保证同一会话的同类事件只能成功落库一次，
 * 凭据插入成功后才允许写 Outbox 与写入播放量增量。</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WatchEventClaim {

    /** 凭据主键 UUID。 */
    private String id;

    /** 用户账号 ID。 */
    private String userId;

    /** 视频公开短码。 */
    private String vid;

    /** 所属观看会话 ID。 */
    private String sessionId;

    /** 事件类型。 */
    private WatchEventType eventType;

    /** 同事务写入的 Outbox 事件 ID，用于事后对账。 */
    private String outboxEventId;

    /** 抢占成功时间。 */
    private LocalDateTime claimedAt;

    /**
     * 构造一条待落库的事件凭据。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param sessionId 会话 ID
     * @param eventType 事件类型
     * @param now 抢占时间
     * @return 事件凭据实体
     */
    public static WatchEventClaim create(String userId, String vid, String sessionId,
                                         WatchEventType eventType, LocalDateTime now) {
        return WatchEventClaim.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId)
                .vid(vid)
                .sessionId(sessionId)
                .eventType(eventType)
                .claimedAt(now)
                .build();
    }
}
