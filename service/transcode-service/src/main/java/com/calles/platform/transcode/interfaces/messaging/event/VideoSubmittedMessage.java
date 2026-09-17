package com.calles.platform.transcode.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 视频提审发布领域事件 (content.video.submitted) 消息载荷契约对象。
 *
 * <p>兼容来自 content-service Outbox 发送的标准提审消息体，忽略未来可能扩展的未知属性。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoSubmittedMessage(
        String videoId,
        String vid,
        String authorId,
        String title,
        String description,
        String videoFileId,
        String coverFileId
) {}
