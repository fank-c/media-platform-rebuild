package com.calles.platform.interaction.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * 视频元数据事件 (content.video.metadata) 消息契约模型。
 *
 * <p>该事件由 content-service 发布，用于让 interaction-service 建立本地视频时长快照。
 * 消费端必须容忍未知新字段，并按 {@code eventId} 做幂等处理。</p>
 *
 * @param eventId 事件唯一标识 (32 位无中划线 UUID)，消费幂等键
 * @param eventType 事件类型标识，固定为 content.video.metadata
 * @param eventVersion 事件契约版本号
 * @param traceId 全链路日志追踪 ID
 * @param occurredAt 事件发生时间 (ISO-8601)
 * @param videoId 视频内部主键 ID
 * @param vid 视频公开业务短码
 * @param duration 视频总时长 (秒)，小于等于 0 视为无效元数据
 * @param metadataVersion 内容元数据版本号，用于识别同一视频的多次元数据发布
 * @param status 内容状态，仅 PUBLISHED 参与播放量与完播判定
 * @param updatedAt 元数据变更时间 (ISO-8601)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoMetadataMessage(
        String eventId,
        String eventType,
        Integer eventVersion,
        String traceId,
        Instant occurredAt,
        String videoId,
        String vid,
        Integer duration,
        Integer metadataVersion,
        String status,
        Instant updatedAt
) {
}
