package com.calles.platform.auth.infrastructure.outbox;

/**
 * 已被当前发布实例租赁的 Outbox 消息；claimToken 用于防止过期实例覆盖新领取者结果。
 *
 * @param eventId 事件 ID
 * @param payload JSON 消息体
 * @param claimToken 本次领取令牌
 * @param attempts 当前发送次数
 */
public record ClaimedOutboxMessage(String eventId, String payload, String claimToken, int attempts) {
}
