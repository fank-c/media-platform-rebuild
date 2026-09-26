package com.calles.platform.interaction.infrastructure.scheduling;

import com.calles.platform.interaction.application.counter.CounterAggregationApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 视频互动统计计数增量后台调度任务触发器。
 *
 * <p>遵循关注点分离原则，仅负责触发调度心跳，所有业务编排、数据认领与清理逻辑均委托 {@link CounterAggregationApplicationService} 执行。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CounterDeltaScheduler {

    /** 计数增量汇总与超期清理应用用例。 */
    private final CounterAggregationApplicationService aggregationService;

    /** 单次批处理汇总或清理最大增量条数。 */
    @Value("${interaction.counter.batch-size:500}")
    private int batchSize;

    /** 已处理历史增量数据保留天数。 */
    @Value("${interaction.counter.retention-days:7}")
    private int retentionDays;

    /**
     * 周期性触发计数增量汇总用例。
     */
    @Scheduled(fixedDelayString = "${interaction.counter.flush-rate-ms:5000}")
    public void aggregate() {
        aggregationService.aggregate(batchSize);
    }

    /**
     * 周期性清理已经汇总且超过保留期的历史增量记录。
     */
    @Scheduled(fixedDelayString = "${interaction.counter.cleanup-rate-ms:3600000}")
    public void cleanup() {
        aggregationService.cleanupProcessed(retentionDays, batchSize);
    }
}
