package com.calles.platform.recommend.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 视频提审发布领域事件 (content.video.submitted) 消息反序列化契约模型。
 *
 * <p>兼容未知新字段拓展，携带 traceId 实现分布式调用链追踪贯通。</p>
 *
 * @param eventId 事件唯一 UUID 标识
 * @param eventType 领域事件类型 (如 content.video.submitted)
 * @param traceId 全链路日志追踪 ID
 * @param videoId 视频全局内部主键 ID (UUID 32位)
 * @param vid 视频公开短码
 * @param authorId 创作者用户 ID
 * @param title 视频标题
 * @param description 视频详细描述
 * @param videoFileId 视频原片文件 ID
 * @param coverFileId 视频封面图片文件 ID
 * @param duration 视频时长 (秒)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoSubmittedMessage(
        String eventId,
        String eventType,
        String traceId,
        String videoId,
        String vid,
        String authorId,
        String title,
        String description,
        String videoFileId,
        String coverFileId,
        Integer duration
) {}
