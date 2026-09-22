package com.calles.platform.user.domain.follow.event;

/**
 * 用户建立关注领域事件载荷，契约版本 v1。
 *
 * @param userId 发起关注的用户账号ID
 * @param targetUserId 被关注的目标创作者账号ID
 * @param occurredAt 事件发生时间契约文本 (ISO-8601)
 */
public record UserFollowedPayload(String userId, String targetUserId, String occurredAt) {
}
