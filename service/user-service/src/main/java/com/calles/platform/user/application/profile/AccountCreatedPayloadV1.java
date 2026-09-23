package com.calles.platform.user.application.profile;

/**
 * {@code auth.account.created.v1} 在 user-service 内部使用的事件载荷。
 *
 * <p>该类型仅表达资料初始化需要读取的账户事实；它不属于 common-core，也不复用 auth-service
 * 的实现类型，从而保持服务间只有版本化 JSON 契约而没有编译期领域依赖。</p>
 *
 * @param accountId 认证服务已持久化的账户 ID
 * @param accountType 账户主体类型；当前消费者仅接受 {@code user}
 * @param createdAt 认证账户创建时间文本；当前资料初始化不使用，允许为空
 */
public record AccountCreatedPayloadV1(String accountId, String accountType, String createdAt) {
}
