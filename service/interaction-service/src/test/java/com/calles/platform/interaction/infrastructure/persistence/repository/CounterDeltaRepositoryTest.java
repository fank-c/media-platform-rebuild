package com.calles.platform.interaction.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.counter.CounterDelta;
import com.calles.platform.interaction.domain.model.counter.CounterType;
import com.calles.platform.interaction.infrastructure.persistence.entity.InteractionCounterDeltaPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.InteractionCounterDeltaMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 计数增量仓储 MyBatis-Plus 实现单元测试。
 */
class CounterDeltaRepositoryTest {

    @Test
    @DisplayName("无活跃业务事务时追加增量必须抛出异常")
    void rejectsAppendOutsideBusinessTransaction() {
        InteractionCounterDeltaMapper mapper = mock(InteractionCounterDeltaMapper.class);
        CounterDeltaRepositoryImpl repository = new CounterDeltaRepositoryImpl(mapper);

        CounterDelta delta = CounterDelta.create("v1", CounterType.VIEW, 1L, "WATCH_PLAY", "w_001");

        assertThatThrownBy(() -> repository.append(delta))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在业务事务内写入");
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("锁定待处理增量正常转换为领域实体列表并包含来源事实字段")
    void delegatesLockPendingForUpdate() {
        InteractionCounterDeltaMapper mapper = mock(InteractionCounterDeltaMapper.class);
        CounterDeltaRepositoryImpl repository = new CounterDeltaRepositoryImpl(mapper);

        LocalDateTime now = LocalDateTime.now();
        InteractionCounterDeltaPO po = InteractionCounterDeltaPO.builder()
                .id(1L)
                .vid("v1")
                .counterType("VIEW")
                .delta(2L)
                .sourceType("WATCH_PLAY")
                .sourceId("w_001")
                .createdAt(now)
                .processedAt(null)
                .build();
        when(mapper.lockPendingForUpdate(10)).thenReturn(List.of(po));

        List<CounterDelta> result = repository.lockPendingForUpdate(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getVid()).isEqualTo("v1");
        assertThat(result.get(0).getType()).isEqualTo(CounterType.VIEW);
        assertThat(result.get(0).getDelta()).isEqualTo(2L);
        assertThat(result.get(0).getSourceType()).isEqualTo("WATCH_PLAY");
        assertThat(result.get(0).getSourceId()).isEqualTo("w_001");
        verify(mapper).lockPendingForUpdate(10);
    }

    @Test
    @DisplayName("P1约束：创建VIEW/SHARE为非正增量时必须拒绝抛出异常")
    void rejectsNonPositiveViewOrShareDelta() {
        assertThatThrownBy(() -> CounterDelta.create("v1", CounterType.VIEW, 0L, "WATCH_PLAY", "w_1"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> CounterDelta.create("v1", CounterType.VIEW, -1L, "WATCH_PLAY", "w_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("播放量和分享量增量必须为严格正整数");

        assertThatThrownBy(() -> CounterDelta.create("v1", CounterType.SHARE, -2L, "SHARE", "key_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("播放量和分享量增量必须为严格正整数");
    }

    @Test
    @DisplayName("P2约束：缺失事实来源类型或标识时必须拒绝抛出异常")
    void rejectsMissingSourceInformation() {
        assertThatThrownBy(() -> CounterDelta.create("v1", CounterType.LIKE, 1L, "", "s_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务事实来源类型不能为空");

        assertThatThrownBy(() -> CounterDelta.create("v1", CounterType.LIKE, 1L, "LIKE_ACTIVE", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("业务事实来源标识不能为空");
    }

    @Test
    @DisplayName("批量标记已处理正常委托 Mapper 执行")
    void delegatesMarkProcessed() {
        InteractionCounterDeltaMapper mapper = mock(InteractionCounterDeltaMapper.class);
        CounterDeltaRepositoryImpl repository = new CounterDeltaRepositoryImpl(mapper);

        LocalDateTime processedAt = LocalDateTime.now();
        repository.markProcessed(List.of(1L, 2L), processedAt);

        verify(mapper).markProcessedBatch(eq(List.of(1L, 2L)), eq(processedAt));
    }

    @Test
    @DisplayName("历史清理正常委托 Mapper 执行")
    void delegatesDeleteProcessedBefore() {
        InteractionCounterDeltaMapper mapper = mock(InteractionCounterDeltaMapper.class);
        CounterDeltaRepositoryImpl repository = new CounterDeltaRepositoryImpl(mapper);

        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        when(mapper.deleteProcessedBefore(threshold, 100)).thenReturn(10);

        int count = repository.deleteProcessedBefore(threshold, 100);

        assertThat(count).isEqualTo(10);
        verify(mapper).deleteProcessedBefore(threshold, 100);
    }

    @Test
    @DisplayName("P1验证：deleteProcessedBefore 注解中必须使用原生 < 字面量，绝不能包含 &lt;")
    void verifyDeleteProcessedBeforeAnnotationSql() throws NoSuchMethodException {
        var method = InteractionCounterDeltaMapper.class.getMethod("deleteProcessedBefore", LocalDateTime.class, int.class);
        var deleteAnnotation = method.getAnnotation(org.apache.ibatis.annotations.Delete.class);
        assertThat(deleteAnnotation).isNotNull();
        String sql = String.join(" ", deleteAnnotation.value());
        assertThat(sql).doesNotContain("&lt;");
        assertThat(sql).contains("processed_at < #{threshold}");
    }
}
