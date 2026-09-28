package com.calles.platform.recommend.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 互动作者行为事件 (interaction.author-action) 消息契约反序列化模型。
 *
 * <p>遵循平台统一事件信封规范，载荷 {@link Payload} 承载创作者维度的行为事实：
 * <ul>
 *   <li>关注：{@code action=FOLLOW}，{@code state=ACTIVE}；</li>
 *   <li>取消关注：{@code action=FOLLOW}，{@code state=INACTIVE}。</li>
 * </ul>
 * </p>
 *
 * @param eventId 事件全局唯一标识
 * @param eventType 领域事件类型标识 (固定为 interaction.author-action)
 * @param eventVersion 事件契约版本号 (固定为 1)
 * @param traceId 全链路日志追踪 ID
 * @param occurredAt 事件发生时间戳 (ISO-8601 UTC 字符串)
 * @param payload 具体业务行为事实载荷
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InteractionAuthorActionMessage(
        String eventId,
        String eventType,
        @JsonProperty("eventVersion") int eventVersion,
        String traceId,
        String occurredAt,
        Payload payload
) {

    public static final String EVENT_TYPE_AUTHOR_ACTION = "interaction.author-action";
    public static final String ACTION_FOLLOW = "FOLLOW";
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_INACTIVE = "INACTIVE";

    /**
     * 作者行为事件具体载荷模型。
     *
     * @param userId 行为发起人账号 ID (关注者)
     * @param authorId 目标创作者账号 ID (被关注者)
     * @param action 交互动作类型 (固定为 FOLLOW)
     * @param state 动作生效状态 (ACTIVE: 已关注, INACTIVE: 已取消关注)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(
            String userId,
            String authorId,
            String action,
            String state
    ) {
    }
}
