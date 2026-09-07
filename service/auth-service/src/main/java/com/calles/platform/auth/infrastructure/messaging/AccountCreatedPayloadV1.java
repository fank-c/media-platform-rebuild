package com.calles.platform.auth.infrastructure.messaging;

/**
 * {@code auth.account.created.v1} 的认证服务本地载荷。
 *
 * <p>该类型只承载 user-service 初始化资料所需的最小账户事实，不包含登录名、密码、邮箱或 Token；
 * 它不是跨服务共享领域模型，其他服务应按自身消费需要定义本地输入类型。</p>
 *
 * @param accountId 已持久化的认证账户 ID
 * @param accountType 账户主体类型；当前 v1 固定为 {@code user}
 * @param createdAt 账户创建时间的 UTC ISO-8601 文本
 */
public record AccountCreatedPayloadV1(String accountId, String accountType, String createdAt) {
}
