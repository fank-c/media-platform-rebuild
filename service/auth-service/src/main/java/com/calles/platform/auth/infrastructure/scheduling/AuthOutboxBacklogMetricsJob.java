package com.calles.platform.auth.infrastructure.scheduling;

import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 独立刷新 Auth Outbox 积压 Gauge 的低频任务。
 *
 * <p>全表聚合不参与发送状态机；发送关闭时仍刷新快照以便观察积压，刷新失败则保留上一次有效值。</p>
 */
@Component
public class AuthOutboxBacklogMetricsJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthOutboxBacklogMetricsJob.class);

    /** Outbox 聚合读取入口。 */
    private final AuthOutboxRepository repository;
    /** 内存 Gauge 快照与刷新失败指标。 */
    private final AuthOperationalMetrics metrics;

    /**
     * @param repository Outbox 仓储
     * @param metrics 指标出口
     */
    public AuthOutboxBacklogMetricsJob(AuthOutboxRepository repository, AuthOperationalMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /** 低频读取积压聚合；数据库异常不改变已经记录的发送结果。 */
    @Scheduled(fixedDelayString = "${auth.outbox.backlog-refresh-interval:60s}")
    public void refresh() {
        try {
            metrics.updateOutboxBacklog(repository.loadBacklogSnapshot());
            metrics.recordOutboxBacklogRefresh(true);
        } catch (RuntimeException exception) {
            metrics.recordOutboxBacklogRefresh(false);
            LOGGER.warn("Outbox 积压指标刷新失败，reason={}", exception.getClass().getSimpleName());
        }
    }
}
