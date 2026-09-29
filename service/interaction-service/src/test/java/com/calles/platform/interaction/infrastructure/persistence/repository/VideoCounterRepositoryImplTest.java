package com.calles.platform.interaction.infrastructure.persistence.repository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.counter.CounterType;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoCounterPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoCounterMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 已汇总视频计数快照仓储 MyBatis-Plus 实现测试。
 */
@ExtendWith(MockitoExtension.class)
class VideoCounterRepositoryImplTest {
    private static final Instant TEST_INSTANT = Instant.parse("2026-09-27T12:00:00Z");
    private static final Clock TEST_CLOCK = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime TEST_TIME = LocalDateTime.ofInstant(TEST_INSTANT, ZoneOffset.UTC);


    @Mock
    private VideoCounterMapper mapper;

    private VideoCounterRepositoryImpl repository;

    @BeforeEach
    void setUp() {
        repository = new VideoCounterRepositoryImpl(mapper);
    }

    @Test
    @DisplayName("findByVid 从已汇总的数据库计数表读取")
    void shouldFindFromDb() {
        VideoCounter sample = VideoCounter.createDefault("vid_1", TEST_TIME);
        when(mapper.selectById("vid_1")).thenReturn(VideoCounterPO.fromDomain(sample));

        Optional<VideoCounter> result = repository.findByVid("vid_1");

        assertThat(result).isPresent();
        assertThat(result.get().getVid()).isEqualTo("vid_1");
    }

    @Test
    @DisplayName("applyDelta 正确分发到各维度的原子累加 Mapper 方法")
    void shouldApplyDeltaCorrectly() {
        repository.applyDelta("vid_1", CounterType.VIEW, 10L, TEST_TIME);
        verify(mapper).applyViewDelta("vid_1", 10L, TEST_TIME);

        repository.applyDelta("vid_1", CounterType.LIKE, 1L, TEST_TIME);
        verify(mapper).applyLikeDelta("vid_1", 1L, TEST_TIME);

        repository.applyDelta("vid_1", CounterType.STAR, -1L, TEST_TIME);
        verify(mapper).applyStarDelta("vid_1", -1L, TEST_TIME);

        repository.applyDelta("vid_1", CounterType.SHARE, 2L, TEST_TIME);
        verify(mapper).applyShareDelta("vid_1", 2L, TEST_TIME);
    }

    @Test
    @DisplayName("applyDelta 对非法入参防御拦截不调底层 Mapper")
    void shouldIgnoreInvalidDeltaParameters() {
        repository.applyDelta(null, CounterType.VIEW, 1L, TEST_TIME);
        repository.applyDelta("vid_1", null, 1L, TEST_TIME);
        repository.applyDelta("vid_1", CounterType.VIEW, 0L, TEST_TIME);

        verifyNoInteractions(mapper);
    }
}
