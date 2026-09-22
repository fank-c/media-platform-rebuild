package com.calles.platform.interaction.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoCounterMapper;
import com.calles.platform.interaction.infrastructure.redis.VideoCounterRedisCache;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 视频互动统计仓储实现类单元测试。
 *
 * <p>验证仓储接口对于增量更新委托、缓存优先读取及降级加载回调的协同行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class VideoCounterRepositoryImplTest {

    /** 模拟持久层 Mapper。 */
    @Mock
    private VideoCounterMapper mapper;

    /** 模拟 Redis 缓存与缓冲组件。 */
    @Mock
    private VideoCounterRedisCache redisCache;

    /** 被测仓储实例。 */
    private VideoCounterRepositoryImpl repository;

    /**
     * 每个测试用例执行前的初始化配置。
     */
    @BeforeEach
    void setUp() {
        repository = new VideoCounterRepositoryImpl(mapper, redisCache);
    }

    @Test
    @DisplayName("增量计数方法正确委托给 Redis 缓存")
    void shouldDelegateAdjustMethodsToRedisCache() {
        repository.incrementViewCount("vid_1", 5);
        verify(redisCache).incrementView("vid_1", 5);

        repository.adjustLikeCount("vid_1", 1);
        verify(redisCache).adjustLike("vid_1", 1);

        repository.adjustStarCount("vid_1", -1);
        verify(redisCache).adjustStar("vid_1", -1);

        repository.incrementShareCount("vid_1", 2);
        verify(redisCache).incrementShare("vid_1", 2);
    }

    @Test
    @DisplayName("findByVid 优先从缓存读取")
    void shouldFindFromCache() {
        VideoCounter sample = VideoCounter.createDefault("vid_1");
        when(redisCache.getCounter(eq("vid_1"), any())).thenReturn(sample);

        Optional<VideoCounter> opt = repository.findByVid("vid_1");

        assertThat(opt).isPresent();
        assertThat(opt.get().getVid()).isEqualTo("vid_1");
    }
}
