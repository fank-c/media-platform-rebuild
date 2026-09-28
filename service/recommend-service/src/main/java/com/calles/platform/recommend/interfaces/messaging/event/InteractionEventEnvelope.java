package com.calles.platform.recommend.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 推荐服务交互领域事件通用信封 (InteractionEventEnvelope)。
 *
 * <p>统一封装交互事件族（包括视频互动事件与作者关注事件）的标准外层元数据。
 * 支持入站消费者以弱类型/流式方式先行提取并校验信封头信息，随后按 {@code eventType} 精确分发。</p>
 *
 * @param eventId 事件全局唯一标识 (UUID)
 * @param eventType 领域事件类型标识 (如 interaction.video-action, interaction.author-action)
 * @param eventVersion 事件契约版本号 (默认为 1)
 * @param traceId 全链路日志追踪 ID
 * @param occurredAt 事件在交互发生端生成的时间戳 (ISO-8601 UTC 字符串)
 * @param payload 具体业务行为事实载荷对象或 JsonNode
 * @param <T> 载荷泛型类型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InteractionEventEnvelope<T>(
        String eventId,
        String eventType,
        @JsonProperty("eventVersion") int eventVersion,
        String traceId,
        String occurredAt,
        T payload
) {
    /** 视频维度互动事件类型标识。 */
    public static final String EVENT_TYPE_VIDEO_ACTION = "interaction.video-action";

    /** 作者维度互动事件类型标识。 */
    public static final String EVENT_TYPE_AUTHOR_ACTION = "interaction.author-action";
}
