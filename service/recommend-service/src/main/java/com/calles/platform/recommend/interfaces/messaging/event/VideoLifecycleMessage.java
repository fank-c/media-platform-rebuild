package com.calles.platform.recommend.interfaces.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 视频生命周期状态变更领域事件 (content.video.offlined / content.video.banned) 契约模型。
 *
 * <p>用于下游推荐服务将已下架或封禁的视频从候选池清退，杜绝已失效内容继续分发。</p>
 *
 * @param eventId 事件唯一 UUID 标识
 * @param eventType 领域事件类型 (content.video.offlined 或 content.video.banned)
 * @param traceId 全链路日志追踪 ID
 * @param videoId 视频全局内部主键 ID
 * @param vid 视频公开短码
 * @param reason 下线或封禁原因说明 (选填)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VideoLifecycleMessage(
        String eventId,
        String eventType,
        String traceId,
        String videoId,
        String vid,
        String reason
) {}
