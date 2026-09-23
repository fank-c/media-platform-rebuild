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

    @Mock
    private com.calles.platform.interaction.application.event.InteractionEventPublisher eventPublisher;

    private WatchHeartbeatApplicationService service;

    @BeforeEach
    void setUp() {
        service = new WatchHeartbeatApplicationService(historyRepository, counterRepository, dedupService, eventPublisher);
    }

    @Test
    @DisplayName("首次心跳时长未达阈值暂不累加播放量且不发布事件")
    void shouldNotIncrementViewCountWhenDurationBelowThreshold() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 2, 2, 100);

        assertThat(history.getLastPosition()).isEqualTo(2);
        assertThat(history.getWatchedDuration()).isEqualTo(2);
        assertThat(history.isCompleted()).isFalse();
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("心跳累计达标且未有持久化记录时自增播放量并写入Outbox")
    void shouldIncrementViewCountWhenThresholdMetAndNotDeduped() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 3, 3, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 6, 3, 100);

        assertThat(history.getWatchedDuration()).isEqualTo(6);
        assertThat(history.getLastValidPlayAt()).isNotNull();
        verify(historyRepository).update(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("持久化防刷窗口期内重复心跳不再次累加播放量或发布事件")
    void shouldNotIncrementViewCountAgainWithinDedupWindow() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 6, 6, 100);
        existing.markValidPlay(java.time.LocalDateTime.now().minusMinutes(10)); // 10分钟前记录过有效播放
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 11, 5, 100);

        assertThat(history.getLastPosition()).isEqualTo(11);
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("超过30分钟持久化窗口后重新计为有效播放并写入Outbox")
    void shouldIncrementViewCountWhenWindowExpired() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 6, 6, 100);
        existing.markValidPlay(java.time.LocalDateTime.now().minusMinutes(35)); // 35分钟前记录过有效播放，已过窗口
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 11, 5, 100);

        assertThat(history.getLastPosition()).isEqualTo(11);
        verify(counterRepository).incrementViewCount("vid_100", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("用户伪删除历史记录后在30分钟内重播，自愈复活但严格防刷，不重复自增播放量或发事件")
    void shouldReviveSoftDeletedRecordAndPreserveDedupWindow() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 6, 6, 100);
        existing.markValidPlay(java.time.LocalDateTime.now().minusMinutes(10)); // 10分钟前有效播放
        existing.markDeleted(); // 用户执行了伪删除
        assertThat(existing.isDeleted()).isTrue();

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 10, 5, 100);

        assertThat(history.isDeleted()).isFalse(); // 自愈复活
        verify(historyRepository).revive(existing); // 调用复活接口
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class)); // 严格防重，不刷播放量
        verify(eventPublisher, never()).publishVideoAction(any()); // 不发重复事件
    }

    @Test
    @DisplayName("用户伪删除历史记录后超过30分钟重播，自愈复活并成功产生新一轮有效播放")
    void shouldReviveSoftDeletedRecordAndCountNewPlayWhenWindowExpired() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 6, 6, 100);
        existing.markValidPlay(java.time.LocalDateTime.now().minusMinutes(40)); // 40分钟前有效播放
        existing.markDeleted(); // 用户执行了伪删除

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 10, 6, 100);

        assertThat(history.isDeleted()).isFalse();
        verify(historyRepository).revive(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("播放进度超过90%自动标记完播")
    void shouldMarkAsCompletedWhenOver90Percent() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 95, 95, 100);

        assertThat(history.isCompleted()).isTrue();
    }
}
