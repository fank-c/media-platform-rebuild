package com.calles.platform.interaction.application.outbox;

import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import java.time.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 互动 Outbox 已发布记录保留清理用例。
 * 清理与派发分离，仅删除已成功发布且超过保留期的记录。
 */
@Service
public class InteractionOutboxCleanupService {

    /** 清理结果日志，不包含事件载荷。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionOutboxCleanupService.class);

    /** 每批删除的持久化事务边界。 */
    private final InteractionOutboxRepository repository;
    /** 清理开关、保留期和批次上限。 */
    private final InteractionOutboxProperties properties;
    /** 计算保留期截止时间的统一时钟。 */
    private final Clock clock;
    /** 记录执行、删除、耗时和失败指标。 */
    private final MeterRegistry meterRegistry;

    /** 注入清理依赖，不执行数据库操作。 */
    public InteractionOutboxCleanupService(InteractionOutboxRepository repository,
                                           InteractionOutboxProperties properties,
                                           Clock clock,
                                           MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 执行受批次数限制的清理，空批次立即停止以避免无意义数据库请求。
     *
     * @return 本轮删除的记录数
     */
    public int cleanup() {
        if (!properties.isCleanupEnabled()) {
            return 0;
        }
        long startedAt = System.nanoTime();
        meterRegistry.counter("interaction.outbox.cleanup.executions").increment();
        try {
            Instant cutoff = clock.instant().minus(properties.getRetention());
            int deleted = 0;
            // 每批提交后记录删除数，后续批失败也不能丢失已完成工作的指标。
            for (int batch = 0; batch < properties.getCleanupMaxBatches(); batch++) {
                int count = repository.deletePublishedBefore(cutoff, properties.getCleanupBatchSize());
                deleted += count;
                meterRegistry.counter("interaction.outbox.cleanup.deleted").increment(count);
                if (count < properties.getCleanupBatchSize()) {
                    break;
                }
            }
            LOGGER.info("Interaction Outbox 清理完成: deleted={}, cutoff={}", deleted, cutoff);
            return deleted;
        } catch (Exception e) {
            meterRegistry.counter("interaction.outbox.cleanup.failures").increment();
            throw e;
        } finally {
            meterRegistry.timer("interaction.outbox.cleanup.duration").record(System.nanoTime() - startedAt,
                    TimeUnit.NANOSECONDS);
        }
    }
}
