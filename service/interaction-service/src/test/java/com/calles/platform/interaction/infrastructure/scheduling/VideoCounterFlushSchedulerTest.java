package com.calles.platform.interaction.infrastructure.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoCounterMapper;
import com.calles.platform.interaction.infrastructure.redis.VideoCounterRedisCache;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 视频互动统计快照异步刷盘调度器单元测试。
 *
 * <p>验证定时任务在脏数据集合为空时的跳过机制，以及存在脏数据时批量提取快照并调用 Mapper 幂等持久化的行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class VideoCounterFlushSchedulerTest {

    /** 模拟缓存与脏标记队列。 */
    @Mock
    private VideoCounterRedisCache redisCache;

    /** 模拟持久层 Mapper。 */
    @Mock
    private VideoCounterMapper counterMapper;

    /** 被测刷盘调度器。 */
    private VideoCounterFlushScheduler scheduler;

    /**
     * 前置配置：构建带有 Mock 依赖的调度器实例。
     */
    @BeforeEach
    void setUp() {
        scheduler = new VideoCounterFlushScheduler(redisCache, counterMapper);
    }

    @Test
    @DisplayName("当无变动视频时跳过批量刷盘")
    void shouldSkipWhenNoDirtyVids() {
        when(redisCache.popDirtyVids(100)).thenReturn(Set.of());

        scheduler.flushDirtyCounters();

        verify(counterMapper, never()).upsertSnapshot(any());
    }

    @Test
    @DisplayName("提取到脏视频清单时批量提取绝对快照并持久化覆盖")
    void shouldFlushDirtyVidsSuccessfully() {
        when(redisCache.popDirtyVids(100)).thenReturn(Set.of("vid_100"));
        VideoCounter snapshot = VideoCounter.createDefault("vid_100");
        snapshot.incrementViewCount(50);
        snapshot.adjustLikeCount(10);
        when(redisCache.getSnapshotForFlush("vid_100")).thenReturn(snapshot);

        scheduler.flushDirtyCounters();

        verify(counterMapper).upsertSnapshot(any());
    }
}
