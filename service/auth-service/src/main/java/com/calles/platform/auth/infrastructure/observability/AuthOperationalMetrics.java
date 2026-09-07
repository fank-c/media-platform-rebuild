package com.calles.platform.auth.infrastructure.observability;

import com.calles.platform.auth.infrastructure.outbox.OutboxBacklogSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 认证事件链路的低基数业务指标，覆盖 Outbox 发布、快速提示、扫描恢复和资料补齐。
 *
 * <p>指标不使用 eventId、accountId、traceId 或异常消息作为标签，避免敏感数据泄露与时序膨胀。</p>
 */
@Component
public class AuthOperationalMetrics {

    /** Micrometer 注册表，只用于创建代码内固定 outcome 的低基数计数器。 */
    private final MeterRegistry registry;
    /** Outbox 发布成功次数，仅表示 Broker ack 且状态成功回写。 */
    private final Counter outboxPublished;
    /** Outbox 发布失败且状态成功回写次数。 */
    private final Counter outboxFailed;
    /** 次数耗尽记录被收敛到 FAILED 的计数。 */
    private final Counter outboxAttemptsExhausted;
    /** 扫描单轮耗时，包含候选发现与逐条处理。 */
    private final Timer outboxScanDuration;
    /** 补齐任务发现的候选账号数。 */
    private final Counter backfillCandidates;
    /** 补齐任务成功写入 Outbox 的账号数。 */
    private final Counter backfillEnqueued;
    /** 补齐任务因并发或已有进度而跳过的账号数。 */
    private final Counter backfillSkipped;
    /** 补齐任务写入失败的账号数。 */
    private final Counter backfillFailed;
    /** Refresh 会话轮换完整请求耗时，不使用会话或凭据作为标签。 */
    private final Timer refreshDuration;
    /** Refresh 条件清理执行结果；failed 包含 Redis 不可用、状态已变化和无可清理对象。 */
    private final Counter refreshAbortFailed;
    /** 当前等待发布记录数，由独立统计任务刷新。 */
    private final AtomicLong pendingOutbox = new AtomicLong();
    /** 当前达到重试上限记录数，由独立统计任务刷新。 */
    private final AtomicLong failedOutbox = new AtomicLong();
    /** 当前最老未发布记录时长，单位秒。 */
    private final AtomicLong oldestUnpublishedAgeSeconds = new AtomicLong();
    /** 最近一次成功积压刷新时刻，UTC epoch 秒；0 表示尚未成功。 */
    private final AtomicLong backlogLastSuccessEpochSeconds = new AtomicLong();

    /**
     * 创建认证事件指标并固定有限 outcome 标签，避免业务标识导致时序膨胀。
     *
     * @param registry Micrometer 指标注册表
     */
    public AuthOperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.outboxPublished = registry.counter("auth_outbox_publish_total", "outcome", "published");
        this.outboxFailed = registry.counter("auth_outbox_publish_total", "outcome", "failed");
        this.outboxAttemptsExhausted = registry.counter("auth_outbox_attempts_exhausted_total");
        this.outboxScanDuration = registry.timer("auth_outbox_scan_duration");
        this.backfillCandidates = registry.counter("auth_profile_backfill_total", "outcome", "candidate");
        this.backfillEnqueued = registry.counter("auth_profile_backfill_total", "outcome", "enqueued");
        this.backfillSkipped = registry.counter("auth_profile_backfill_total", "outcome", "skipped");
        this.backfillFailed = registry.counter("auth_profile_backfill_total", "outcome", "failed");
        this.refreshDuration = registry.timer("auth_refresh_duration");
        this.refreshAbortFailed = registry.counter("auth_refresh_abort_total", "outcome", "failed");
        Gauge.builder("auth_outbox_pending", pendingOutbox, AtomicLong::get).register(registry);
        Gauge.builder("auth_outbox_failed", failedOutbox, AtomicLong::get).register(registry);
        Gauge.builder("auth_outbox_oldest_unpublished_age_seconds", oldestUnpublishedAgeSeconds,
                AtomicLong::get).register(registry);
        Gauge.builder("auth_outbox_backlog_last_success_epoch_seconds", backlogLastSuccessEpochSeconds,
                AtomicLong::get).register(registry);
    }

    /**
     * 记录 Refresh 会话轮换的固定结果和端到端应用耗时。标签只允许代码定义的有限集合，避免
     * sid、rotationId、账户标识或异常文本进入监控时序。
     *
     * @param outcome success、invalid、disabled、unavailable 或 failed
     * @param durationNanos 从接收刷新请求到返回/拒绝的耗时，负数按零处理
     */
    public void recordRefresh(String outcome, long durationNanos) {
        registry.counter("auth_refresh_total", "outcome", requireRefreshOutcome(outcome)).increment();
        refreshDuration.record(Math.max(0, durationNanos), TimeUnit.NANOSECONDS);
    }

    /**
     * 记录 Refresh 已取得轮换资格后的条件清理失败。该指标只计失败，不将正常清理成功重复计入
     * refresh 成功量；失败可能是 Redis 不可用、logout 已先删除会话或轮换状态已被安全改变。
     *
     * @param aborted 条件 abort 脚本明确删除本请求 in-flight 会话时为 true
     */
    public void recordRefreshAbort(boolean aborted) {
        if (!aborted) {
            refreshAbortFailed.increment();
        }
    }

    /** 记录一条 Outbox 已得到 Broker 确认且未被退回。 */
    public void recordOutboxPublished() {
        outboxPublished.increment();
    }

    /** 记录一次 Outbox 发布失败尝试且当前 token 已完成状态回写。 */
    public void recordOutboxFailed() {
        outboxFailed.increment();
    }

    /**
     * 记录快速提示路径的固定结果。
     *
     * @param outcome accepted、rejected、disabled、no_transaction、submission_failed 或 task_failed
     */
    public void recordFastDispatchHint(String outcome) {
        registry.counter("auth_outbox_fast_dispatch_hint_total", "outcome", requireFastHintOutcome(outcome)).increment();
    }

    /**
     * 记录条件领取或 token 所有权结果。
     *
     * @param outcome claimed、skipped、lost、disabled 或 dispatch_error
     */
    public void recordOutboxClaim(String outcome) {
        registry.counter("auth_outbox_claim_total", "outcome", requireClaimOutcome(outcome)).increment();
    }

    /** 记录一条次数耗尽记录被安全收敛为 FAILED。 */
    public void recordOutboxAttemptsExhausted() {
        outboxAttemptsExhausted.increment();
    }

    /**
     * 记录扫描轮次耗时。
     *
     * @param durationNanos 本轮耗时，必须为非负值
     */
    public void recordOutboxScan(long durationNanos) {
        outboxScanDuration.record(Math.max(0, durationNanos), TimeUnit.NANOSECONDS);
    }

    /**
     * 记录积压聚合刷新结果。
     *
     * @param success 是否成功读取并写入内存快照
     */
    public void recordOutboxBacklogRefresh(boolean success) {
        registry.counter("auth_outbox_backlog_refresh_total", "outcome", success ? "success" : "failed").increment();
        if (success) {
            backlogLastSuccessEpochSeconds.set(System.currentTimeMillis() / 1_000L);
        }
    }

    /**
     * 记录补齐扫描发现的候选数量。
     *
     * @param count 当前批次候选数，必须为非负数
     */
    public void recordBackfillCandidates(int count) {
        backfillCandidates.increment(Math.max(0, count));
    }

    /** 记录一个候选账号已成功创建补齐 Outbox。 */
    public void recordBackfillEnqueued() {
        backfillEnqueued.increment();
    }

    /** 记录一个候选账号因并发或已有进度而被安全跳过。 */
    public void recordBackfillSkipped() {
        backfillSkipped.increment();
    }

    /** 记录一个候选账号生成补齐事件失败，事务应已回滚并等待下一轮重试。 */
    public void recordBackfillFailed() {
        backfillFailed.increment();
    }

    /**
     * 原子刷新 Outbox 积压聚合值，避免指标采集线程中直接访问数据库。
     *
     * @param snapshot 仓储读取的积压快照
     */
    public void updateOutboxBacklog(OutboxBacklogSnapshot snapshot) {
        pendingOutbox.set(snapshot.pendingCount());
        failedOutbox.set(snapshot.failedCount());
        oldestUnpublishedAgeSeconds.set(snapshot.oldestUnpublishedAgeSeconds());
    }

    /** 校验 Refresh outcome 只能来自代码固定集合。 */
    private String requireRefreshOutcome(String outcome) {
        return switch (outcome) {
            case "success", "invalid", "disabled", "unavailable", "failed" -> outcome;
            default -> throw new IllegalArgumentException("未知 Refresh outcome");
        };
    }

    /** 校验快速提示 outcome 只能来自代码固定集合。 */
    private String requireFastHintOutcome(String outcome) {
        return switch (outcome) {
            case "accepted", "rejected", "disabled", "no_transaction", "submission_failed", "task_failed" -> outcome;
            default -> throw new IllegalArgumentException("未知 Outbox 快速提示 outcome");
        };
    }

    /** 校验领取 outcome 只能来自代码固定集合。 */
    private String requireClaimOutcome(String outcome) {
        return switch (outcome) {
            case "claimed", "skipped", "lost", "disabled", "dispatch_error" -> outcome;
            default -> throw new IllegalArgumentException("未知 Outbox 领取 outcome");
        };
    }

}
