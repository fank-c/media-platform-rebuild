package com.calles.platform.interaction.infrastructure.scheduling;

import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.infrastructure.outbox.model.InteractionOutboxStatus;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 采样待处理与失败记录积压，清理开关关闭时仍持续提供状态指标。 */
@Component
public class InteractionOutboxBacklogMetrics {

    /** 采样故障与失败积压的日志出口。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionOutboxBacklogMetrics.class);
    /** 只监控仍需投递或人工处理的状态。 */
    private static final InteractionOutboxStatus[] MONITORED = {
            InteractionOutboxStatus.PENDING, InteractionOutboxStatus.PROCESSING, InteractionOutboxStatus.FAILED
    };

    /** 仅读取自属发件箱表的采样仓储。 */
    private final InteractionOutboxRepository repository;
    /** 判断积压是否由派发关闭导致。 */
    private final InteractionOutboxProperties properties;
    /** 计算年龄的统一时钟。 */
    private final Clock clock;
    /** 保持强引用的数量采样值，避免 Gauge 被回收。 */
    private final EnumMap<InteractionOutboxStatus, AtomicLong> counts = new EnumMap<>(InteractionOutboxStatus.class);
    /** 最老事件年龄秒数，空状态和未来时间取零。 */
    private final EnumMap<InteractionOutboxStatus, AtomicLong> ages = new EnumMap<>(InteractionOutboxStatus.class);

    /** 注册各状态数量和最老事件年龄的仪表。 */
    public InteractionOutboxBacklogMetrics(InteractionOutboxRepository repository,
                                           InteractionOutboxProperties properties, Clock clock,
                                           MeterRegistry registry) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        for (InteractionOutboxStatus status : MONITORED) {
            AtomicLong count = new AtomicLong();
            AtomicLong age = new AtomicLong();
            counts.put(status, count);
            ages.put(status, age);
            registry.gauge("interaction.outbox.backlog.count", Tags.of("status", status.databaseValue()), count);
            registry.gauge("interaction.outbox.backlog.oldest.age.seconds", Tags.of("status", status.databaseValue()), age);
        }
    }

    /** 独立采样，不允许数据库异常阻断派发或清理。 */
    @Scheduled(fixedDelayString = "#{T(org.springframework.boot.convert.DurationStyle).detectAndParse('${interaction.outbox.poll-interval:5s}').toMillis()}")
    public void sample() {
        try {
            Instant now = clock.instant();
            for (InteractionOutboxStatus status : MONITORED) {
                long count = repository.countByStatus(status);
                Instant oldest = repository.oldestOccurredAt(status);
                counts.get(status).set(count);
                ages.get(status).set(oldest == null ? 0 : Math.max(0, now.getEpochSecond() - oldest.getEpochSecond()));
            }
            if (!properties.isDispatchEnabled() && counts.get(InteractionOutboxStatus.PENDING).get() > 0) {
                LOGGER.debug("Interaction Outbox 派发关闭，PENDING 积压为配置预期: count={}",
                        counts.get(InteractionOutboxStatus.PENDING).get());
            }
            if (counts.get(InteractionOutboxStatus.FAILED).get() > 0) {
                LOGGER.warn("Interaction Outbox 存在 FAILED 记录，需人工排查: count={}",
                        counts.get(InteractionOutboxStatus.FAILED).get());
            }
        } catch (Exception e) {
            LOGGER.error("Interaction Outbox 积压采样失败", e);
        }
    }
}
