package com.calles.platform.auth.infrastructure.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * auth_outbox 持久化适配器，负责记录、条件领取、租约和条件状态回写，不承担消息发送。
 *
 * <p>快速提示与扫描均通过本仓储按 ID 条件领取。候选发现不持有租约，只有工作线程实际开始处理
 * 时才递增 attempts 并设置租约，避免排队任务提前占住待发送记录。</p>
 */
@Repository
public class AuthOutboxRepository {

    /** Outbox 专用 Mapper，只处理 SQL 执行与结果映射。 */
    private final AuthOutboxMapper outboxMapper;
    /** 用于资格边界、租约和退避计算的 UTC 时钟。 */
    private final Clock clock;

    /**
     * @param outboxMapper Outbox SQL Mapper
     * @param authClock 认证服务统一 UTC 时钟
     */
    public AuthOutboxRepository(AuthOutboxMapper outboxMapper, Clock authClock) {
        this.outboxMapper = outboxMapper;
        this.clock = authClock;
    }

    /**
     * 将事件记录写入调用方的本地事务。
     *
     * @param record 待发布事件
     */
    public void insert(AuthOutboxRecord record) {
        // 使用同一个发生时间初始化事件和首次投递时间，保持注册事务内的原有时序。
        Timestamp occurredAt = Timestamp.from(record.occurredAt());
        outboxMapper.insert(record, occurredAt, AuthOutboxStatus.PENDING.databaseValue(), occurredAt);
    }

    /**
     * 只读发现本轮可以尝试发送的候选 ID；读取本身不创建租约。
     *
     * @param limit 单轮候选上限
     * @param maxAttempts 自动领取次数上限
     * @return 稳定排序的候选 ID
     */
    public List<String> findClaimableEventIds(int limit, int maxAttempts) {
        Instant now = clock.instant();
        return outboxMapper.findClaimableEventIds(AuthOutboxStatus.PENDING.databaseValue(), Timestamp.from(now),
                AuthOutboxStatus.PROCESSING.databaseValue(), Timestamp.from(now), maxAttempts, limit);
    }

    /**
     * 只读发现应收敛为 FAILED 的到期耗尽记录。
     *
     * @param limit 单轮收敛上限
     * @param maxAttempts 自动领取次数上限
     * @return 稳定排序的耗尽事件 ID
     */
    public List<String> findExhaustedEventIds(int limit, int maxAttempts) {
        Instant now = clock.instant();
        return outboxMapper.findExhaustedEventIds(AuthOutboxStatus.PENDING.databaseValue(), Timestamp.from(now),
                AuthOutboxStatus.PROCESSING.databaseValue(), Timestamp.from(now), maxAttempts, limit);
    }

    /**
     * 在独立短事务中按完整资格条件领取一条事件，并读取同一 token 的发送快照。
     *
     * @param eventId 事件 ID
     * @param owner 当前进程发布实例标识
     * @param lease 租约长度
     * @param maxAttempts 自动领取次数上限
     * @return 成功领取的快照；空表示竞争失败、未到期、终态、次数耗尽或记录不存在
     */
    @Transactional
    public ClaimedOutboxMessage claimByEventId(String eventId, String owner, Duration lease, int maxAttempts) {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();
        // 条件 UPDATE 是多实例、扫描与快速通道争用时唯一的领取裁决点。
        int updated = outboxMapper.markClaimedIfEligible(eventId, AuthOutboxStatus.PROCESSING.databaseValue(), owner,
                Timestamp.from(now.plus(lease)), token, maxAttempts, AuthOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now), AuthOutboxStatus.PROCESSING.databaseValue(), Timestamp.from(now));
        if (updated != 1) {
            return null;
        }
        // 在同一领取事务内按 token 读取快照，提交之后才允许 RabbitMQ 发送。
        ClaimedOutboxMessage message = outboxMapper.findClaimedMessage(eventId,
                AuthOutboxStatus.PROCESSING.databaseValue(), token);
        if (message == null) {
            throw new IllegalStateException("Outbox 领取成功后未找到同一令牌快照");
        }
        return message;
    }

    /**
     * 将次数已耗尽且满足到期资格的记录原子收敛为 FAILED。
     *
     * @param eventId 事件 ID
     * @param maxAttempts 自动领取次数上限
     * @return true 表示本次完成终态收敛；false 表示状态已被其他实例推进或尚未到期
     */
    @Transactional
    public boolean markExhaustedIfEligible(String eventId, int maxAttempts) {
        Instant now = clock.instant();
        int updated = outboxMapper.markExhaustedIfEligible(eventId, AuthOutboxStatus.FAILED.databaseValue(),
                "ATTEMPTS_EXHAUSTED", maxAttempts, AuthOutboxStatus.PENDING.databaseValue(), Timestamp.from(now),
                AuthOutboxStatus.PROCESSING.databaseValue(), Timestamp.from(now));
        return updated == 1;
    }

    /**
     * 仅由持有当前领取令牌的实例确认发布成功。
     *
     * @param message 当前实例领取的消息快照
     * @return true 表示状态已更新，false 表示租约令牌已失效
     */
    @Transactional
    public boolean markPublished(ClaimedOutboxMessage message) {
        int updated = outboxMapper.markPublished(message.eventId(), AuthOutboxStatus.PUBLISHED.databaseValue(),
                Timestamp.from(clock.instant()), AuthOutboxStatus.PROCESSING.databaseValue(), message.claimToken());
        return updated == 1;
    }

    /**
     * 记录失败并执行有界指数退避；仅当前领取令牌仍有效时才会改变状态。
     *
     * @param message 当前实例领取的消息快照
     * @param maxAttempts 自动发布尝试上限
     * @param errorCode 低敏感度失败分类，不写入异常详情
     * @return true 表示失败结果已写入；false 表示领取权已丢失
     */
    @Transactional
    public boolean markFailed(ClaimedOutboxMessage message, int maxAttempts, String errorCode) {
        boolean exhausted = message.attempts() >= maxAttempts;
        long baseDelaySeconds = Math.min(300L, 1L << Math.min(8, Math.max(0, message.attempts() - 1)));
        // 在基础退避上增加最多 20% 正向抖动，避免 Broker 恢复时多个实例同步重试形成尖峰。
        long jitterBound = Math.max(1L, baseDelaySeconds / 5L);
        long delaySeconds = Math.min(300L,
                baseDelaySeconds + ThreadLocalRandom.current().nextLong(jitterBound + 1));
        int updated = outboxMapper.markFailed(message.eventId(),
                (exhausted ? AuthOutboxStatus.FAILED : AuthOutboxStatus.PENDING).databaseValue(),
                Timestamp.from(clock.instant().plusSeconds(delaySeconds)), errorCode,
                AuthOutboxStatus.PROCESSING.databaseValue(), message.claimToken());
        return updated == 1;
    }

    /**
     * 读取低基数积压聚合值；空结果或负时长统一归一化，避免指标线程暴露数据库异常值。
     *
     * @return 当前 Outbox 积压快照
     */
    public OutboxBacklogSnapshot loadBacklogSnapshot() {
        OutboxBacklogSnapshot snapshot = outboxMapper.loadBacklogSnapshot(AuthOutboxStatus.PENDING.databaseValue(),
                AuthOutboxStatus.FAILED.databaseValue(), AuthOutboxStatus.PROCESSING.databaseValue());
        if (snapshot == null) {
            return new OutboxBacklogSnapshot(0, 0, 0);
        }
        return new OutboxBacklogSnapshot(Math.max(0, snapshot.pendingCount()), Math.max(0, snapshot.failedCount()),
                Math.max(0, snapshot.oldestUnpublishedAgeSeconds()));
    }
}
