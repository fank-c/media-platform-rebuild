package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.domain.repository.WatchHistoryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private com.calles.platform.interaction.application.event.InteractionEventPublisher eventPublisher;

    private WatchHeartbeatApplicationService service;

    @BeforeEach
    void setUp() {
        service = new WatchHeartbeatApplicationService(
                historyRepository,
                counterRepository,
                eventPublisher,
                Duration.ofHours(6)
        );
    }

    @Test
    @DisplayName("用户首次点进视频起播：建立历史记录、累加播放量并发布起播事件")
    void shouldIncrementViewCountOnFirstPlayStart() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.startPlay("vid_100", "user_01");

        assertThat(history.getVid()).isEqualTo("vid_100");
        assertThat(history.getUserId()).isEqualTo("user_01");
        assertThat(history.getLastPosition()).isEqualTo(0);
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository).incrementViewCount("vid_100", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("用户在6小时防刷冷却期内再次点进视频：仅刷新活跃时间，不重复累加播放量与事件")
    void shouldNotIncrementViewCountWhenPlayStartWithinRepeatWindow() {
        WatchHistory existing = WatchHistory.createForPlay("user_01", "vid_100");
        // 模拟 1 小时前的观看
        existing.recordPlayStart(LocalDateTime.now().minusHours(1));
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.startPlay("vid_100", "user_01");

        assertThat(history.getVid()).isEqualTo("vid_100");
        verify(historyRepository).update(existing);
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("用户超出6小时防刷冷却期后再次点进视频：算作新一轮播放并累加播放量")
    void shouldIncrementViewCountWhenPlayStartAfterRepeatWindow() {
        WatchHistory existing = WatchHistory.createForPlay("user_01", "vid_100");
        // 模拟 7 小时前的观看（已超出 6 小时窗口）
        existing.recordPlayStart(LocalDateTime.now().minusHours(7));
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.startPlay("vid_100", "user_01");

        assertThat(history.getVid()).isEqualTo("vid_100");
        verify(historyRepository).update(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("用户伪删除历史记录后在冷却期内重播：自愈复活但严格防刷，不重复增加播放量")
    void shouldReviveSoftDeletedRecordAndPreserveRepeatWindow() {
        WatchHistory existing = WatchHistory.createForPlay("user_01", "vid_100");
        existing.recordPlayStart(LocalDateTime.now().minusHours(1));
        existing.markDeleted();
        assertThat(existing.isDeleted()).isTrue();

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.startPlay("vid_100", "user_01");

        assertThat(history.isDeleted()).isFalse();
        verify(historyRepository).revive(existing);
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("心跳上报仅更新播放断点与完播状态，绝不累加播放量")
    void shouldUpdateProgressAndNotIncrementViewCountOnHeartbeat() {
        WatchHistory existing = WatchHistory.createForPlay("user_01", "vid_100");
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 95, 10, 100);

        assertThat(history.getLastPosition()).isEqualTo(95);
        assertThat(history.getWatchedDuration()).isEqualTo(10);
        assertThat(history.isCompleted()).isTrue();
        verify(historyRepository).update(existing);
        // 心跳绝对不碰播放量加减
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("客户端未显式调用起播直接发送心跳时的安全保底处理")
    void shouldFallbackAndCreateHistoryIfHeartbeatReceivedDirectly() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 5, 5, 100);

        assertThat(history.getLastPosition()).isEqualTo(5);
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository).incrementViewCount("vid_100", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }
}
