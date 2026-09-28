package com.calles.platform.recommend.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 互动视频行为事件 (interaction.video-action) 消息契约反序列化模型。
 *
 * <p>遵循平台统一事件信封规范，载荷 {@link Payload} 承载具体的行为事实：
 * <ul>
 *   <li>点赞 / 取消点赞：{@code action=LIKE}，{@code state=ACTIVE/INACTIVE}；</li>
 *   <li>收藏 / 取消收藏：{@code action=STAR}，{@code state=ACTIVE/INACTIVE}；</li>
 *   <li>分享：{@code action=SHARE}，{@code state=ACTIVE}；</li>
 *   <li>有效观看资格：{@code action=WATCH_VIEW_QUALIFIED}，携带会话及核验有效时长；</li>
 *   <li>完播：{@code action=WATCH_COMPLETED}，携带会话及核验有效时长。</li>
 * </ul>
 * </p>
 *
 * @param eventId 事件全局唯一标识
 * @param eventType 领域事件类型标识 (固定为 interaction.video-action)
 * @param eventVersion 事件契约版本号
 * @param traceId 全链路日志追踪 ID
 * @param occurredAt 事件在交互端生成的时间戳 (ISO-8601 UTC 字符串)
 * @param payload 具体业务行为事实载荷
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InteractionVideoActionMessage(
        String eventId,
        String eventType,
        @JsonProperty("eventVersion") int eventVersion,
        String traceId,
        String occurredAt,
        Payload payload
) {

    /**
     * 互动视频行为事件具体载荷模型。
     *
     * @param userId 交互发起人账号 ID
     * @param vid 目标视频公开短码
     * @param action 行为类型字面量 (LIKE/STAR/SHARE/WATCH_VIEW_QUALIFIED/WATCH_COMPLETED)
     * @param state 状态字面量 (ACTIVE/INACTIVE，仅点赞/收藏/分享使用)
     * @param sessionId 观看会话 ID (仅观看行为使用)
     * @param creditedDuration 服务端核验认可的有效观看时长秒数 (仅观看行为使用)
     * @param videoDuration 视频当时时长快照秒数 (仅观看行为使用)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(
            String userId,
            String vid,
            String action,
            String state,
            String sessionId,
            Integer creditedDuration,
            Integer videoDuration
    ) {}
}
