package com.calles.platform.recommend.domain.model.event;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 互动事件消费幂等记录领域实体 (EventConsumedRecord)。
 *
 * <p>核心职责：
 * <ul>
 *   <li>记录推荐服务已成功消费处理的领域事件唯一标识；</li>
 *   <li>构筑应用与仓储层物理防重屏障，防御 RabbitMQ 网络重试或重复投递导致的画像重复演进；</li>
 *   <li>支持记录消费发生时间戳，便于后续执行滑动保留期窗口清理。</li>
 * </ul>
 * </p>
 */
@Getter
public class EventConsumedRecord {

    /** 事件全局唯一标识 (32位无短横线UUID或标准UUID)。 */
    private final String eventId;

    /** 领域事件类型标识 (如 interaction.video-action)。 */
    private final String eventType;

    /** 行为触发用户账号 ID。 */
    private final String userId;

    /** 目标视频公开短码。 */
    private final String vid;

    /** 具体行为类型字面量 (如 LIKE/STAR/SHARE 等)。 */
    private final String action;

    /** 消费入库完成时间。 */
    private final LocalDateTime consumedAt;

    /**
     * 全参构造函数，用于仓储数据还原。
     *
     * @param eventId 事件全局唯一标识
     * @param eventType 领域事件类型标识
     * @param userId 行为触发用户账号 ID
     * @param vid 目标视频公开短码
     * @param action 具体行为类型字面量
     * @param consumedAt 消费入库完成时间
     */
    public EventConsumedRecord(String eventId, String eventType, String userId, String vid,
                               String action, LocalDateTime consumedAt) {
        this.eventId = Objects.requireNonNull(eventId, "事件ID不能为空");
        this.eventType = Objects.requireNonNull(eventType, "事件类型不能为空");
        this.userId = Objects.requireNonNull(userId, "用户ID不能为空");
        this.vid = Objects.requireNonNull(vid, "视频短码不能为空");
        this.action = Objects.requireNonNull(action, "行为类型不能为空");
        this.consumedAt = consumedAt != null ? consumedAt : LocalDateTime.now();
    }

    /**
     * 工厂方法：新建一笔事件消费记录。
     *
     * @param eventId 事件全局唯一标识
     * @param eventType 领域事件类型标识
     * @param userId 行为触发用户账号 ID
     * @param vid 目标视频公开短码
     * @param action 具体行为类型字面量
     * @return 初始化的消费记录实例
     */
    public static EventConsumedRecord create(String eventId, String eventType,
                                             String userId, String vid, String action) {
        return new EventConsumedRecord(eventId, eventType, userId, vid, action, LocalDateTime.now());
    }
}
