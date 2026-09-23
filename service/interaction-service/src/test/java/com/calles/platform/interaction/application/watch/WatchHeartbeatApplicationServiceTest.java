package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.domain.repository.WatchHistoryRepository;
import com.calles.platform.interaction.infrastructure.redis.HeartbeatDedupService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WatchHeartbeatApplicationServiceTest {

    @Mock
    private WatchHistoryRepository historyRepository;

    @Mock
    private VideoCounterRepository counterRepository;

    @Mock
    private HeartbeatDedupService dedupService;

    private WatchHeartbeatApplicationService service;

    @BeforeEach
    void setUp() {
        service = new WatchHeartbeatApplicationService(historyRepository, counterRepository, dedupService);
    }

    @Test
    @DisplayName("首次心跳时长未达阈值暂不累加播放量")
    void shouldNotIncrementViewCountWhenDurationBelowThreshold() {
        when(historyRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 2, 2, 100);

        assertThat(history.getLastPosition()).isEqualTo(2);
        assertThat(history.getWatchedDuration()).isEqualTo(2);
        assertThat(history.isCompleted()).isFalse();
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
    }

    @Test
    @DisplayName("心跳累计达标且未重复计费时自增播放量")
    void shouldIncrementViewCountWhenThresholdMetAndNotDeduped() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 3, 3, 100);
        when(historyRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(dedupService.tryAcquireFirstPlay("user_01", "vid_100")).thenReturn(true);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 6, 3, 100);

        assertThat(history.getWatchedDuration()).isEqualTo(6);
        verify(historyRepository).update(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);
    }

    @Test
    @DisplayName("防刷窗口期内重复心跳不再次累加播放量")
    void shouldNotIncrementViewCountAgainWithinDedupWindow() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 6, 6, 100);
        when(historyRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(dedupService.tryAcquireFirstPlay("user_01", "vid_100")).thenReturn(false);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 11, 5, 100);

        assertThat(history.getLastPosition()).isEqualTo(11);
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
    }

    @Test
    @DisplayName("播放进度超过90%自动标记完播")
    void shouldMarkAsCompletedWhenOver90Percent() {
        when(historyRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 95, 95, 100);

        assertThat(history.isCompleted()).isTrue();
    }
}
