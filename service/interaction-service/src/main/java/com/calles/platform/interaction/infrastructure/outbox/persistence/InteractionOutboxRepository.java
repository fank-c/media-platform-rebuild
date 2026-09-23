package com.calles.platform.interaction.infrastructure.outbox.persistence;

import com.calles.platform.interaction.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.interaction.infrastructure.outbox.model.InteractionOutboxRecord;
import com.calles.platform.interaction.infrastructure.outbox.model.InteractionOutboxStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * interaction_outbox 专用持久化仓储适配器。
 */
@Repository
public class InteractionOutboxRepository {

    private final InteractionOutboxMapper outboxMapper;
    private final Clock clock;

    public InteractionOutboxRepository(InteractionOutboxMapper outboxMapper,
                                       @Autowired(required = false) Clock clock) {
        this.outboxMapper = outboxMapper;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /**
     * 将领域事件记录插入当前调用方本地事务中。
     *
     * @param record 待落库事件记录
     */
    public void insert(InteractionOutboxRecord record) {
        Timestamp occurredAt = Timestamp.from(record.occurredAt());
        outboxMapper.insert(record, occurredAt, InteractionOutboxStatus.PENDING.databaseValue(), occurredAt);
    }

    /**
     * 只读扫描当前可供锁定的待投递事件 ID 列表。
     *
     * @param limit 单次扫描上限
     * @param maxAttempts 最大重试上限
     * @return 可认领事件 ID 列表
     */
    public List<String> findClaimableEventIds(int limit, int maxAttempts) {
        Instant now = clock.instant();
        return outboxMapper.findClaimableEventIds(
                InteractionOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                InteractionOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now),
                maxAttempts,
                limit
        );
    }

    /**
     * 只读扫描已耗尽最大重试次数且租约已过期的事件 ID 列表。
     *
     * @param limit 单次扫描上限
     * @param maxAttempts 最大重试上限
     * @return 耗尽事件 ID 列表
     */
    public List<String> findExhaustedEventIds(int limit, int maxAttempts) {
        Instant now = clock.instant();
        return outboxMapper.findExhaustedEventIds(
                InteractionOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                InteractionOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now),
                maxAttempts,
                limit
        );
    }

    /**
     * 在独立短事务中通过 CAS 条件原子认领一条待投递事件，并提取发送快照。
     *
     * @param eventId 事件 ID
     * @param owner 租约持有者标识
     * @param lease 租约时长
     * @param maxAttempts 最大尝试次数
     * @return 认领成功的消息快照，若已被其他实例抢占则返回 null
     */
    @Transactional
    public ClaimedOutboxMessage claimByEventId(String eventId, String owner, Duration lease, int maxAttempts) {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();

        int updated = outboxMapper.markClaimedIfEligible(
                eventId,
                InteractionOutboxStatus.PROCESSING.databaseValue(),
                owner,
                Timestamp.from(now.plus(lease)),
                token,
                maxAttempts,
                InteractionOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                InteractionOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now)
        );

        if (updated != 1) {
            return null;
        }

        ClaimedOutboxMessage message = outboxMapper.findClaimedMessage(
                eventId,
                InteractionOutboxStatus.PROCESSING.databaseValue(),
                token
        );
        if (message == null) {
            throw new IllegalStateException("Interaction Outbox 认领成功后未检索到同一令牌的发送快照: eventId=" + eventId);
        }
        return message;
    }

    /**
     * 将次数耗尽且租约已过期的事件原子置位收敛为 FAILED 终态。
     *
     * @param eventId 事件 ID
     * @param maxAttempts 最大重试次数
     * @return 是否成功置位
     */
    @Transactional
    public boolean markExhaustedIfEligible(String eventId, int maxAttempts) {
        Instant now = clock.instant();
        int updated = outboxMapper.markExhaustedIfEligible(
                eventId,
                InteractionOutboxStatus.FAILED.databaseValue(),
                "ATTEMPTS_EXHAUSTED",
                maxAttempts,
                InteractionOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                InteractionOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now)
        );
        return updated == 1;
    }

    /**
     * 仅由持有当前有效 claimToken 的实例将状态标记为发布成功 (PUBLISHED)，并清理租约信息。
     *
     * @param message 认领快照
     * @return 是否成功更新
     */
    @Transactional
    public boolean markPublished(ClaimedOutboxMessage message) {
        int updated = outboxMapper.markPublished(
                message.eventId(),
                InteractionOutboxStatus.PUBLISHED.databaseValue(),
                Timestamp.from(clock.instant()),
                InteractionOutboxStatus.PROCESSING.databaseValue(),
                message.claimToken()
        );
        return updated == 1;
    }

    /**
     * 登记投递失败结果，计算有界指数退避并释放租约。
     *
     * @param message 认领快照
     * @param maxAttempts 最大重试上限
     * @param errorCode 错误原因代码
     * @return 是否成功更新
     */
    @Transactional
    public boolean markFailed(ClaimedOutboxMessage message, int maxAttempts, String errorCode) {
        boolean exhausted = message.attempts() >= maxAttempts;
        long baseDelaySeconds = Math.min(300L, 1L << Math.min(8, Math.max(0, message.attempts() - 1)));
        long jitterBound = Math.max(1L, baseDelaySeconds / 5L);
        long delaySeconds = Math.min(300L, baseDelaySeconds + ThreadLocalRandom.current().nextLong(jitterBound + 1));

        int updated = outboxMapper.markFailed(
                message.eventId(),
                (exhausted ? InteractionOutboxStatus.FAILED : InteractionOutboxStatus.PENDING).databaseValue(),
                Timestamp.from(clock.instant().plusSeconds(delaySeconds)),
                errorCode,
                InteractionOutboxStatus.PROCESSING.databaseValue(),
                message.claimToken()
        );
        return updated == 1;
    }
}
