package com.calles.platform.recommend.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * 视频正式发布上线领域事件 (content.video.published) 消息契约模型。
 *
 * <p>用于下游推荐服务将新作品准入推荐候选池，兼容未知新字段拓展，携带 traceId 实现调用链贯通。</p>
 *
 * @param eventId 事件唯一 UUID 标识
 * @param eventType 领域事件类型 (如 content.video.published)
 * @param traceId 全链路日志追踪 ID
 * @param videoId 视频全局内部主键 ID (UUID 32位)
 * @param vid 视频公开短码 (Base62)
 * @param authorId 创作者用户 ID
 * @param domainTagIds 关联领域标签 ID 列表字符串 (选填)
 * @param topicTagIds 关联主题标签 ID 列表字符串 (选填)
 * @param publishedAt 正式发布时间
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoPublishedMessage(
        String eventId,
        String eventType,
        String traceId,
        String videoId,
        String vid,
        String authorId,
        String domainTagIds,
        String topicTagIds,
        LocalDateTime publishedAt
) {}
