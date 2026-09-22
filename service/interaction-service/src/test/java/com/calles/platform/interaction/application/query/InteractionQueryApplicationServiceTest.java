package com.calles.platform.interaction.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.application.like.LikeApplicationService;
import com.calles.platform.interaction.application.star.StarApplicationService;
import com.calles.platform.interaction.application.watch.WatchHeartbeatApplicationService;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InteractionQueryApplicationServiceTest {

    @Mock
    private LikeApplicationService likeService;

    @Mock
    private StarApplicationService starService;

    @Mock
    private WatchHeartbeatApplicationService watchService;

    @Mock
    private VideoCounterRepository counterRepository;

    private InteractionQueryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new InteractionQueryApplicationService(likeService, starService, watchService, counterRepository);
    }

    @Test
    @DisplayName("正确聚合登录用户的互动快照状态")
    void shouldReturnCorrectMyStateForUser() {
        when(likeService.isLiked("vid_100", "user_01")).thenReturn(true);
        when(starService.isStarred("vid_100", "user_01")).thenReturn(false);

        WatchHistory watch = WatchHistory.create("user_01", "vid_100", 45, 45, 120);
        when(watchService.getWatchProgress("vid_100", "user_01")).thenReturn(Optional.of(watch));

        InteractionQueryApplicationService.UserInteractionState state = service.getMyState("vid_100", "user_01");

        assertThat(state.isLiked()).isTrue();
        assertThat(state.isStarred()).isFalse();
        assertThat(state.getLastWatchPosition()).isEqualTo(45);
        assertThat(state.isCompleted()).isFalse();
    }

    @Test
    @DisplayName("未登录时返回安全的默认快照")
    void shouldReturnDefaultStateWhenAnonymous() {
        InteractionQueryApplicationService.UserInteractionState state = service.getMyState("vid_100", null);

        assertThat(state.isLiked()).isFalse();
        assertThat(state.isStarred()).isFalse();
        assertThat(state.getLastWatchPosition()).isZero();
    }

    @Test
    @DisplayName("批量查询计数器时自动补齐缺失视频为全0计数")
    void shouldPadDefaultCounterWhenNotFoundInBatch() {
        VideoCounter c1 = VideoCounter.createDefault("vid_1");
        c1.adjustLikeCount(10);
        when(counterRepository.findByVids(List.of("vid_1", "vid_2"))).thenReturn(List.of(c1));

        Map<String, VideoCounter> result = service.getBatchVideoStats(List.of("vid_1", "vid_2"));

        assertThat(result).hasSize(2);
        assertThat(result.get("vid_1").getLikeCount()).isEqualTo(10);
        assertThat(result.get("vid_2").getLikeCount()).isZero();
    }
}
