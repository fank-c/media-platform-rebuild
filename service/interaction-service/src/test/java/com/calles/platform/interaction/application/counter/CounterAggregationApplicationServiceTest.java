package com.calles.platform.interaction.application.counter;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.counter.CounterDelta;
import com.calles.platform.interaction.domain.model.counter.CounterType;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 计数增量后台汇总与清理应用服务单元测试。
 */
@ExtendWith(MockitoExtension.class)
class CounterAggregationApplicationServiceTest {
    private static final Instant TEST_INSTANT = Instant.parse("2026-09-27T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime TEST_TIME = LocalDateTime.ofInstant(TEST_INSTANT, ZoneOffset.UTC);


    @Mock
    private CounterDeltaRepository deltas;

    @Mock
    private VideoCounterRepository snapshots;

    @Test
    @DisplayName("按视频与计数类型维度聚合增量并批量标记已处理")
    void aggregatesByVideoAndTypeBeforeMarkingProcessed() {
        LocalDateTime now = TEST_TIME;
        when(deltas.lockPendingForUpdate(10)).thenReturn(List.of(
                CounterDelta.reconstitute(1L, "v1", CounterType.STAR, 1L, now, null),
                CounterDelta.reconstitute(2L, "v1", CounterType.STAR, -1L, now, null),
                CounterDelta.reconstitute(3L, "v1", CounterType.VIEW, 2L, now, null)
        ));

        int aggregated = new CounterAggregationApplicationService(deltas, snapshots, TEST_CLOCK).aggregate(10);

        assertThat(aggregated).isEqualTo(3);
        verify(snapshots).applyDelta("v1", CounterType.VIEW, 2L, TEST_TIME);
        verify(snapshots, never()).applyDelta("v1", CounterType.STAR, 0L, TEST_TIME);
        verify(deltas).markProcessed(List.of(1L, 2L, 3L), TEST_TIME);
    }

    @Test
    @DisplayName("快照更新异常时不标记增量已处理并向上抛出异常")
    void doesNotMarkProcessedWhenSnapshotUpdateFails() {
        LocalDateTime now = TEST_TIME;
        when(deltas.lockPendingForUpdate(10)).thenReturn(List.of(
                CounterDelta.reconstitute(1L, "v1", CounterType.VIEW, 1L, now, null)
        ));
        doThrow(new IllegalStateException("snapshot failure")).when(snapshots).applyDelta("v1", CounterType.VIEW, 1L, TEST_TIME);

        assertThrows(IllegalStateException.class,
                () -> new CounterAggregationApplicationService(deltas, snapshots, TEST_CLOCK).aggregate(10));
        verify(deltas, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("清理超期增量用例正常计算阈值并委托仓储执行")
    void delegatesCleanupToRepository() {
        when(deltas.deleteProcessedBefore(any(), any(Integer.class))).thenReturn(5);

        int deleted = new CounterAggregationApplicationService(deltas, snapshots, TEST_CLOCK).cleanupProcessed(7, 100);

        assertThat(deleted).isEqualTo(5);
        verify(deltas).deleteProcessedBefore(TEST_TIME.minusDays(7), 100);
    }
}
