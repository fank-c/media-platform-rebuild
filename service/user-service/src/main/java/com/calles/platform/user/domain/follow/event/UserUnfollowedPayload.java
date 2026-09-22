package com.calles.platform.user.domain.follow.event;

/**
 * 用户取消关注领域事件载荷，契约版本 v1。
 *
 * @param userId 发起取消关注的用户账号ID
 * @param targetUserId 被取消关注的目标创作者账号ID
 * @param occurredAt 事件发生时间契约文本 (ISO-8601)
 */
public record UserUnfollowedPayload(String userId, String targetUserId, String occurredAt) {
}
