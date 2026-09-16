package com.calles.platform.audit.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 视频提审领域事件业务载荷对象。
 *
 * <p>职责说明：承载内容微服务发布的视频元数据及媒体文件凭证快照，隔离内部领域实体。</p>
 *
 * @param videoId 视频全局唯一主键 ID (UUID 32位)
 * @param vid 视频对外公开短码 (如 cv10086)
 * @param authorId 创作者用户 ID
 * @param title 视频标题快照
 * @param description 视频简介文本快照
 * @param coverFileId 封面图片文件资产 ID
 * @param videoFileId 主视频文件资产 ID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoSubmittedPayload(
        String videoId,
        String vid,
        String authorId,
        String title,
        String description,
        String coverFileId,
        String videoFileId
) {
}
