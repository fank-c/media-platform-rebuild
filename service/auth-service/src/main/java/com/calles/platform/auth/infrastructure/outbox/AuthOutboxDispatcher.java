package com.calles.platform.auth.infrastructure.outbox;

import com.calles.platform.auth.config.AuthOutboxProperties;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Auth Outbox 的按 ID 统一分发器。
 *
 * <p>快速提示和扫描均在实际开始执行时调用本类，以同一条条件领取语句裁决竞争。候选列表仅用于
 * 推进扫描进度，不预先持有租约，也不保留发送快照。</p>
 */
@Component
public class AuthOutboxDispatcher {

    /** Outbox 仓储，提供短事务条件领取和终态收敛。 */
    private final AuthOutboxRepository repository;
    /** 统一单条发送器，包含 Confirm 与 token 条件回写。 */
    private final AuthOutboxPublisher publisher;
    /** 运行期开关及批次、租约、重试参数。 */
    private final AuthOutboxProperties properties;
    /** 指标出口，不使用 eventId 等业务标识做标签。 */
    private final AuthOperationalMetrics metrics;
    /** 当前进程的稳定领取拥有者标识。 */
    private final String owner = UUID.randomUUID().toString();

    /**
     * @param repository Outbox 仓储
     * @param publisher 统一单条发送器
     * @param properties 运行参数
     * @param metrics 指标出口
     */
    public AuthOutboxDispatcher(AuthOutboxRepository repository, AuthOutboxPublisher publisher,
            AuthOutboxProperties properties, AuthOperationalMetrics metrics) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 处理一个提示或扫描候选 ID；竞争失败是正常路径，不发送也不增加失败计数。
     *
     * @param eventId 已提交事件 ID 或扫描候选 ID
     */
    public void dispatchByEventId(String eventId) {
        if (!properties.isEnabled()) {
            metrics.recordOutboxClaim("disabled");
            return;
        }
        // 只有工作线程真正开始执行时才领取，队列等待期间不会消耗 attempts 或租约。
        ClaimedOutboxMessage claimed = repository.claimByEventId(eventId, owner, properties.getLease(),
                properties.getMaxAttempts());
        if (claimed != null) {
            metrics.recordOutboxClaim("claimed");
            publisher.publish(claimed);
            return;
        }
        // 已耗尽且到期的记录不应永远占用候选批次；有效租约不会被此操作提前终结。
        if (repository.markExhaustedIfEligible(eventId, properties.getMaxAttempts())) {
            metrics.recordOutboxAttemptsExhausted();
        } else {
            metrics.recordOutboxClaim("skipped");
        }
    }

    /**
     * 扫描一轮候选并逐条即时领取，任何一条异常不得取消本轮其他候选。
     */
    public void dispatchScanBatch() {
        if (!properties.isEnabled()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            List<String> eventIds = repository.findClaimableEventIds(properties.getBatchSize(),
                    properties.getMaxAttempts());
            for (String eventId : eventIds) {
                try {
                    dispatchByEventId(eventId);
                } catch (RuntimeException exception) {
                    // 单条故障交给租约或退避恢复，扫描继续推进其他候选。
                    metrics.recordOutboxClaim("dispatch_error");
                }
            }
            // 普通候选与耗尽收敛分别受 batchSize 限制，避免一类长期挤掉另一类。
            for (String eventId : repository.findExhaustedEventIds(properties.getBatchSize(),
                    properties.getMaxAttempts())) {
                if (repository.markExhaustedIfEligible(eventId, properties.getMaxAttempts())) {
                    metrics.recordOutboxAttemptsExhausted();
                }
            }
        } finally {
            metrics.recordOutboxScan(System.nanoTime() - startedAt);
        }
    }
}
