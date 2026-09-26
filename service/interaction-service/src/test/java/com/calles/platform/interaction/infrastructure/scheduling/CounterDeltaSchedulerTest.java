package com.calles.platform.interaction.infrastructure.scheduling;

import static org.mockito.Mockito.verify;

import com.calles.platform.interaction.application.counter.CounterAggregationApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 计数增量后台调度器单元测试。
 *
 * <p>验证调度器仅触发应用服务，不直接接触仓储与数据访问层。</p>
 */
@ExtendWith(MockitoExtension.class)
class CounterDeltaSchedulerTest {

    @Mock
    private CounterAggregationApplicationService aggregationService;

    private CounterDeltaScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CounterDeltaScheduler(aggregationService);
        ReflectionTestUtils.setField(scheduler, "batchSize", 500);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 7);
    }

    @Test
    @DisplayName("汇总调度轮次正常委托应用服务执行")
    void delegatesAggregationToApplicationService() {
        scheduler.aggregate();
        verify(aggregationService).aggregate(500);
    }

    @Test
    @DisplayName("清理调度轮次正常委托应用服务执行")
    void delegatesCleanupToApplicationService() {
        scheduler.cleanup();
        verify(aggregationService).cleanupProcessed(7, 500);
    }
}
