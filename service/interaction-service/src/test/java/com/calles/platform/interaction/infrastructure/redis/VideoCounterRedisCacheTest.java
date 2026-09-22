package com.calles.platform.interaction.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 视频互动统计缓存与缓冲层单元测试。
 *
 * <p>重点验证当 RedisTemplate 为 null 时透明触发的内存降级模式，
 * 确保读冷加载、原子增量、脏标记与批量聚合逻辑在无独立 Redis 实例下依然稳健可用。</p>
 */
class VideoCounterRedisCacheTest {

    /** 被测缓存与缓冲组件实例（以内存降级模式运行）。 */
    private VideoCounterRedisCache cache;

    /**
     * 前置配置：注入 null 以启用内存自动降级测试分支。
     */
    @BeforeEach
    void setUp() {
        // 传入 null 模拟 Redis 缺失时的内存自动降级模式
        cache = new VideoCounterRedisCache(null);
    }

    @Test
    @DisplayName("冷加载从DB回调获取并在内存缓存中建立快照")
    void shouldLoadFromDbAndCache() {
        VideoCounter dbVal = VideoCounter.createDefault("vid_1");
        dbVal.incrementViewCount(100);

        VideoCounter counter = cache.getCounter("vid_1", () -> dbVal);

        assertThat(counter.getViewCount()).isEqualTo(100);

        // 第二次获取直接走缓存
        VideoCounter cached = cache.getCounter("vid_1", () -> null);
        assertThat(cached.getViewCount()).isEqualTo(100);
    }

    @Test
    @DisplayName("原子自增字段并成功标记脏集合")
    void shouldIncrementFieldAndMarkDirty() {
        cache.incrementView("vid_test", 5);
        cache.adjustLike("vid_test", 2);
        cache.adjustStar("vid_test", 1);
        cache.incrementShare("vid_test", 3);

        VideoCounter counter = cache.getSnapshotForFlush("vid_test");
        assertThat(counter.getViewCount()).isEqualTo(5);
        assertThat(counter.getLikeCount()).isEqualTo(2);
        assertThat(counter.getStarCount()).isEqualTo(1);
        assertThat(counter.getShareCount()).isEqualTo(3);

        Set<String> dirty = cache.popDirtyVids(10);
        assertThat(dirty).contains("vid_test");

        // 弹出后脏集合清空
        Set<String> empty = cache.popDirtyVids(10);
        assertThat(empty).isEmpty();
    }

    @Test
    @DisplayName("批量查询能够自动聚合缓存与缺失项冷加载")
    void shouldBatchGetWithMissingDbLoader() {
        cache.incrementView("vid_1", 10);

        List<VideoCounter> list = cache.getBatchCounters(List.of("vid_1", "vid_2"), missing -> {
            VideoCounter c2 = VideoCounter.createDefault("vid_2");
            c2.incrementViewCount(20);
            return List.of(c2);
        });

        assertThat(list).hasSize(2);
        assertThat(list).extracting(VideoCounter::getVid).containsExactlyInAnyOrder("vid_1", "vid_2");
    }
}
