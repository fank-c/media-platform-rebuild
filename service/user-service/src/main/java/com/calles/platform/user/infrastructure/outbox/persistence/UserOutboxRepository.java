package com.calles.platform.user.infrastructure.outbox.persistence;

import com.calles.platform.user.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.user.infrastructure.outbox.model.UserOutboxRecord;
import com.calles.platform.user.infrastructure.outbox.model.UserOutboxStatus;
import com.calles.platform.user.infrastructure.persistence.mapper.outbox.UserOutboxMapper;
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
 * user_outbox 专用持久化仓储适配器。
 */
@Repository
public class UserOutboxRepository {

    private final UserOutboxMapper outboxMapper;
    private final Clock clock;

    public UserOutboxRepository(UserOutboxMapper outboxMapper, Clock clock) {
        this.outboxMapper = outboxMapper;
        this.clock = clock;
    }

    /**
     * 将领域事件记录插入当前调用方本地事务中。
     *
     * @param record 待落库事件记录
     */
    public void insert(UserOutboxRecord record) {
        Timestamp occurredAt = Timestamp.from(record.occurredAt());
        outboxMapper.insert(record, occurredAt, UserOutboxStatus.PENDING.databaseValue(), occurredAt);
    }

    /**
     * 只读扫描当前可供锁定的待投递事件 ID 列表。
     */
    public List<String> findClaimableEventIds(int limit, int maxAttempts) {
        Instant now = clock.instant();
        return outboxMapper.findClaimableEventIds(
                UserOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                UserOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now),
                maxAttempts,
                limit
        );
    }

    /**
     * 只读扫描已耗尽最大重试次数且租约已过期的事件 ID 列表。
     */
    public List<String> findExhaustedEventIds(int limit, int maxAttempts) {
        Instant now = clock.instant();
        return outboxMapper.findExhaustedEventIds(
                UserOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                UserOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now),
                maxAttempts,
                limit
        );
    }

    /**
     * 在独立短事务中通过 CAS 条件原子认领一条待投递事件，并提取发送快照。
     */
    @Transactional
    public ClaimedOutboxMessage claimByEventId(String eventId, String owner, Duration lease, int maxAttempts) {
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();

        int updated = outboxMapper.markClaimedIfEligible(
                eventId,
                UserOutboxStatus.PROCESSING.databaseValue(),
                owner,
                Timestamp.from(now.plus(lease)),
                token,
                maxAttempts,
                UserOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                UserOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now)
        );

        if (updated != 1) {
            return null;
        }

        ClaimedOutboxMessage message = outboxMapper.findClaimedMessage(
                eventId,
                UserOutboxStatus.PROCESSING.databaseValue(),
                token
        );
        if (message == null) {
            throw new IllegalStateException("User Outbox 认领成功后未检索到同一令牌的发送快照: eventId=" + eventId);
        }
        return message;
    }

    /**
     * 将次数耗尽且租约已过期的事件原子置位收敛为 FAILED 终态。
     */
    @Transactional
    public boolean markExhaustedIfEligible(String eventId, int maxAttempts) {
        Instant now = clock.instant();
        int updated = outboxMapper.markExhaustedIfEligible(
                eventId,
                UserOutboxStatus.FAILED.databaseValue(),
                "ATTEMPTS_EXHAUSTED",
                maxAttempts,
                UserOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                UserOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now)
        );
        return updated == 1;
    }

    /**
     * 仅由持有当前有效 claimToken 的实例将状态标记为发布成功 (PUBLISHED)，并清理租约信息。
     */
    @Transactional
    public boolean markPublished(ClaimedOutboxMessage message) {
        int updated = outboxMapper.markPublished(
                message.eventId(),
                UserOutboxStatus.PUBLISHED.databaseValue(),
                Timestamp.from(clock.instant()),
                UserOutboxStatus.PROCESSING.databaseValue(),
                message.claimToken()
        );
        return updated == 1;
    }

    /**
     * 登记投递失败结果，计算有界指数退避并释放租约。
     */
    @Transactional
    public boolean markFailed(ClaimedOutboxMessage message, int maxAttempts, String errorCode) {
        boolean exhausted = message.attempts() >= maxAttempts;
        long baseDelaySeconds = Math.min(300L, 1L << Math.min(8, Math.max(0, message.attempts() - 1)));
        long jitterBound = Math.max(1L, baseDelaySeconds / 5L);
        long delaySeconds = Math.min(300L, baseDelaySeconds + ThreadLocalRandom.current().nextLong(jitterBound + 1));

        int updated = outboxMapper.markFailed(
                message.eventId(),
                (exhausted ? UserOutboxStatus.FAILED : UserOutboxStatus.PENDING).databaseValue(),
                Timestamp.from(clock.instant().plusSeconds(delaySeconds)),
                errorCode,
                UserOutboxStatus.PROCESSING.databaseValue(),
                message.claimToken()
        );
        return updated == 1;
    }
}
