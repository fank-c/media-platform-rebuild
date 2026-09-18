package com.calles.platform.content.infrastructure.outbox.persistence;

import com.calles.platform.content.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.content.infrastructure.outbox.model.ContentOutboxRecord;
import com.calles.platform.content.infrastructure.outbox.model.ContentOutboxStatus;
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
 * content_outbox 专用持久化仓储适配器。
 *
 * <p>职责与机制说明：
 * <ul>
 *   <li><b>所属边界</b>：发件箱基础设施层核心仓储，专职发件箱状态机的持久化调度与 CAS 原子防重控制；</li>
 *   <li><b>无网络副作用</b>：只负责数据库条件读写，绝对不承担 RabbitMQ 网络消息发布；</li>
 *   <li><b>CAS 认领</b>：单条消息在发送前必须通过短事务成功占用租约并获得不可变发送快照。</li>
 * </ul>
 * </p>
 */
@Repository
public class ContentOutboxRepository {

    /** Outbox 专用持久化 Mapper。 */
    private final ContentOutboxMapper outboxMapper;

    /** 统一系统时钟。 */
    private final Clock clock;

    public ContentOutboxRepository(ContentOutboxMapper outboxMapper, Clock clock) {
        this.outboxMapper = outboxMapper;
        this.clock = clock;
    }

    /**
     * 将领域事件记录插入当前调用方本地事务中。
     *
     * @param record 待落库事件记录
     */
    public void insert(ContentOutboxRecord record) {
        // 步骤 1：以业务事实发生时间为基准，初始化初次允许拉取尝试时间
        Timestamp occurredAt = Timestamp.from(record.occurredAt());
        outboxMapper.insert(record, occurredAt, ContentOutboxStatus.PENDING.databaseValue(), occurredAt);
    }

    /**
     * 只读扫描当前可供锁定的待投递事件 ID 列表（包含重试到期及租约超时的事件）。
     *
     * @param limit 单轮候选记录数量上限
     * @param maxAttempts 自动投递次数上限
     * @return 候选事件全局唯一 ID 列表
     */
    public List<String> findClaimableEventIds(int limit, int maxAttempts) {
        Instant now = clock.instant();
        return outboxMapper.findClaimableEventIds(
                ContentOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                ContentOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now),
                maxAttempts,
                limit
        );
    }

    /**
     * 只读扫描已耗尽最大重试次数且租约已过期的事件 ID 列表。
     *
     * @param limit 单轮收敛记录上限
     * @param maxAttempts 自动投递次数上限
     * @return 耗尽事件 ID 列表
     */
    public List<String> findExhaustedEventIds(int limit, int maxAttempts) {
        Instant now = clock.instant();
        return outboxMapper.findExhaustedEventIds(
                ContentOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                ContentOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now),
                maxAttempts,
                limit
        );
    }

    /**
     * 在独立短事务中通过 CAS 条件原子认领一条待投递事件，并提取对应的发送快照。
     *
     * @param eventId 目标事件 ID
     * @param owner 当前实例唯一节点标识
     * @param lease 租约有效时长
     * @param maxAttempts 最大允许尝试次数
     * @return 成功认领的消息不可变快照；若竞争失败或未就绪则返回 null
     */
    @Transactional
    public ClaimedOutboxMessage claimByEventId(String eventId, String owner, Duration lease, int maxAttempts) {
        // 步骤 1：生成本次认领独占的 claimToken
        Instant now = clock.instant();
        String token = UUID.randomUUID().toString();

        // 步骤 2：执行条件 UPDATE，原子占有租约并递增 attempts
        int updated = outboxMapper.markClaimedIfEligible(
                eventId,
                ContentOutboxStatus.PROCESSING.databaseValue(),
                owner,
                Timestamp.from(now.plus(lease)),
                token,
                maxAttempts,
                ContentOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                ContentOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now)
        );

        if (updated != 1) {
            return null;
        }

        // 步骤 3：在同一事务中凭 claimToken 提取发送快照
        ClaimedOutboxMessage message = outboxMapper.findClaimedMessage(
                eventId,
                ContentOutboxStatus.PROCESSING.databaseValue(),
                token
        );
        if (message == null) {
            throw new IllegalStateException("Outbox 认领成功后未检索到同一令牌的发送快照: eventId=" + eventId);
        }
        return message;
    }

    /**
     * 将次数耗尽且租约已过期的事件原子置位收敛为 FAILED 终态。
     *
     * @param eventId 目标事件 ID
     * @param maxAttempts 最大重试上限
     * @return true 若更新成功，false 否则
     */
    @Transactional
    public boolean markExhaustedIfEligible(String eventId, int maxAttempts) {
        Instant now = clock.instant();
        int updated = outboxMapper.markExhaustedIfEligible(
                eventId,
                ContentOutboxStatus.FAILED.databaseValue(),
                "ATTEMPTS_EXHAUSTED",
                maxAttempts,
                ContentOutboxStatus.PENDING.databaseValue(),
                Timestamp.from(now),
                ContentOutboxStatus.PROCESSING.databaseValue(),
                Timestamp.from(now)
        );
        return updated == 1;
    }

    /**
     * 仅由持有当前有效 claimToken 的实例将状态标记为发布成功 (PUBLISHED)，并清理租约信息。
     *
     * @param message 认领成功的消息快照
     * @return true 若成功更新，false 表示租约令牌已失效或被覆盖
     */
    @Transactional
    public boolean markPublished(ClaimedOutboxMessage message) {
        int updated = outboxMapper.markPublished(
                message.eventId(),
                ContentOutboxStatus.PUBLISHED.databaseValue(),
                Timestamp.from(clock.instant()),
                ContentOutboxStatus.PROCESSING.databaseValue(),
                message.claimToken()
        );
        return updated == 1;
    }

    /**
     * 登记投递失败结果，计算有界指数退避并释放租约。
     *
     * @param message 当前持有租约的消息快照
     * @param maxAttempts 自动尝试次数上限
     * @param errorCode 错误原因类型标识
     * @return true 若状态已回写，false 表示租约已失效
     */
    @Transactional
    public boolean markFailed(ClaimedOutboxMessage message, int maxAttempts, String errorCode) {
        // 步骤 1：判断是否耗尽重试次数
        boolean exhausted = message.attempts() >= maxAttempts;

        // 步骤 2：计算有界指数退避时长 (1s, 2s, 4s, 8s ... 上限 300s)
        long baseDelaySeconds = Math.min(300L, 1L << Math.min(8, Math.max(0, message.attempts() - 1)));
        // 步骤 3：增加最大 20% 抖动，防范多实例并发重试风暴
        long jitterBound = Math.max(1L, baseDelaySeconds / 5L);
        long delaySeconds = Math.min(300L, baseDelaySeconds + ThreadLocalRandom.current().nextLong(jitterBound + 1));

        // 步骤 4：更新发件箱记录，重置租约并设定下次重试时间
        int updated = outboxMapper.markFailed(
                message.eventId(),
                (exhausted ? ContentOutboxStatus.FAILED : ContentOutboxStatus.PENDING).databaseValue(),
                Timestamp.from(clock.instant().plusSeconds(delaySeconds)),
                errorCode,
                ContentOutboxStatus.PROCESSING.databaseValue(),
                message.claimToken()
        );
        return updated == 1;
    }
}
