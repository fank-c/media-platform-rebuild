package com.calles.platform.auth.infrastructure.persistence;

import java.sql.Timestamp;

/**
 * 资料补齐候选账号的最小持久化快照。
 *
 * <p>该对象只包含生成账号创建事件所需的 ID 和创建时间，不能替代完整 {@code AuthAccount} 实体。</p>
 *
 * @param accountId 认证账户 ID
 * @param createdAt 认证账户创建时间，保持 JDBC Timestamp 边界
 */
public record ProfileBackfillCandidate(String accountId, Timestamp createdAt) {
}
