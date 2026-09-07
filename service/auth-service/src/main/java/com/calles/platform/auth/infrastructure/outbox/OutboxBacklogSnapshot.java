package com.calles.platform.auth.infrastructure.outbox;

/**
 * Outbox 积压快照，只承载监控聚合值，不包含事件载荷或业务标识。
 *
 * @param pendingCount 等待发布的 PENDING 记录数
 * @param failedCount 达到自动重试上限的 FAILED 记录数
 * @param oldestUnpublishedAgeSeconds 最老未发布记录距当前的秒数，无积压时为 0
 */
public record OutboxBacklogSnapshot(long pendingCount, long failedCount, long oldestUnpublishedAgeSeconds) {
}
