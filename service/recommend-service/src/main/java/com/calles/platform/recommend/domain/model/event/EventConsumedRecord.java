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
 *   <li>全面支持视频维度互动（如点赞/收藏/分享/完播）与创作者维度交互（关注/取关）的留痕；</li>
 *   <li>支持记录消费发生时间戳，便于后续执行滑动保留期窗口清理。</li>
 * </ul>
 * </p>
 */
@Getter
public class EventConsumedRecord {

    /** 事件全局唯一标识 (32位无短横线UUID或标准UUID)。 */
    private final String eventId;

    /** 领域事件类型标识 (如 interaction.video-action 或 interaction.author-action)。 */
    private final String eventType;

    /** 行为触发用户账号 ID。 */
    private final String userId;

    /** 目标视频公开短码 (作者维度交互可为空)。 */
    private final String vid;

    /** 目标作者账号 ID (视频维度交互可为空)。 */
    private final String authorId;

    /** 具体行为类型字面量 (如 LIKE/STAR/SHARE/FOLLOW 等)。 */
    private final String action;

    /** 动作生效状态字面量 (如 ACTIVE/INACTIVE)。 */
    private final String state;

    /** 消费入库完成时间。 */
    private final LocalDateTime consumedAt;

    /**
     * 全参构造函数，用于仓储数据还原与新业务构造。
     *
     * @param eventId 事件全局唯一标识
     * @param eventType 领域事件类型标识
     * @param userId 行为触发用户账号 ID
     * @param vid 目标视频公开短码
     * @param authorId 目标创作者账号 ID
     * @param action 具体行为类型字面量
     * @param state 动作状态
     * @param consumedAt 消费入库完成时间
     */
    public EventConsumedRecord(String eventId, String eventType, String userId, String vid,
                               String authorId, String action, String state, LocalDateTime consumedAt) {
        this.eventId = Objects.requireNonNull(eventId, "事件ID不能为空");
        this.eventType = Objects.requireNonNull(eventType, "事件类型不能为空");
        this.userId = Objects.requireNonNull(userId, "用户ID不能为空");
        this.action = Objects.requireNonNull(action, "行为类型不能为空");
        this.vid = vid;
        this.authorId = authorId;
        this.state = state;
        this.consumedAt = consumedAt != null ? consumedAt : LocalDateTime.now();
    }

    /**
     * 推荐工厂方法：新建一笔视频互动事件消费记录。
     *
     * @param eventId 事件全局唯一标识
     * @param eventType 领域事件类型标识
     * @param userId 行为触发用户账号 ID
     * @param vid 目标视频公开短码
     * @param action 行为类型
     * @param state 行为状态
     * @return 初始化的消费记录实例
     */
    public static EventConsumedRecord createForVideo(String eventId, String eventType,
                                                     String userId, String vid, String action, String state) {
        return new EventConsumedRecord(eventId, eventType, userId, vid, null, action, state, LocalDateTime.now());
    }

    /**
     * 推荐工厂方法：新建一笔作者维度互动事件消费记录。
     *
     * @param eventId 事件全局唯一标识
     * @param eventType 领域事件类型标识
     * @param userId 行为触发用户账号 ID
     * @param authorId 目标创作者账号 ID
     * @param action 行为类型 (如 FOLLOW)
     * @param state 行为状态 (ACTIVE/INACTIVE)
     * @return 初始化的消费记录实例
     */
    public static EventConsumedRecord createForAuthor(String eventId, String eventType,
                                                      String userId, String authorId, String action, String state) {
        return new EventConsumedRecord(eventId, eventType, userId, null, authorId, action, state, LocalDateTime.now());
    }
}
