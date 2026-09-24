package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

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
    @DisplayName("用户首次发心跳：自动建立历史记录、累加播放量并发布 PLAY_START 事件")
    void shouldCreateHistoryAndIncrementViewOnFirstHeartbeat() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 10, 10, 100);

        assertThat(history.getVid()).isEqualTo("vid_100");
        assertThat(history.getUserId()).isEqualTo("user_01");
        assertThat(history.getLastPosition()).isEqualTo(10);
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository).incrementViewCount("vid_100", 1L);

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY_START);
    }

    @Test
    @DisplayName("同一观看会话内（6小时内）持续心跳：仅更新播放断点，不重复累加播放量与起播事件")
    void shouldNotIncrementViewCountWithinSameSession() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 10, 10, 100);
        existing.recordHeartbeat(20, 10, 100); // 活跃时间在当前
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 30, 10, 100);

        assertThat(history.getLastPosition()).isEqualTo(30);
        verify(historyRepository).update(existing);
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("彻底离开视频超过6小时后重新访问：算作新一轮观看会话，累加播放量并发布 PLAY_START")
    void shouldIncrementViewCountWhenReturnAfterSessionCooldown() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 20, 20, 100);
        // 模拟 7 小时前的最后一次心跳（已彻底离开超过 6 小时）
        existing.recordPlayStart(LocalDateTime.now().minusHours(7));
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 25, 5, 100);

        assertThat(history.getLastPosition()).isEqualTo(25);
        verify(historyRepository).update(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY_START);
    }

    @Test
    @DisplayName("心跳进度达90%且CAS置位成功：标记完播并触发 PLAY_COMPLETE 事件")
    void shouldTriggerPlayCompleteWhenCasSucceeds() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 80, 80, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.markCompletedIfUncompleted(existing.getId())).thenReturn(1); // CAS 成功

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 92, 12, 100);

        assertThat(history.isCompleted()).isTrue();
        verify(historyRepository).update(existing);
        verify(historyRepository).markCompletedIfUncompleted(existing.getId());

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY_COMPLETE);
    }

    @Test
    @DisplayName("多端并发心跳达90%时：CAS仅允许成功一次，防范完播事件双发")
    void shouldPreventDuplicateCompleteEventsWhenCasReturnsZero() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 85, 85, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        // 模拟另一台设备已在同一瞬间把 completed 从 0 改成了 1，当前心跳 CAS 返回 0
        when(historyRepository.markCompletedIfUncompleted(existing.getId())).thenReturn(0);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 95, 10, 100);

        verify(historyRepository).update(existing);
        verify(historyRepository).markCompletedIfUncompleted(existing.getId());
        // CAS 返回 0 时不得发布完播事件
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("多端同毫秒首次心跳触发唯一键冲突时自愈：转入已有历史更新分支")
    void shouldGracefullyRecoverFromConcurrentFirstHeartbeatInsert() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100"))
                .thenReturn(Optional.empty()) // 首次查空
                .thenReturn(Optional.of(WatchHistory.create("user_01", "vid_100", 0, 0, 100))); // 冲突后重查获得另一设备已插入的实体

        org.mockito.Mockito.doThrow(new DuplicateKeyException("Duplicate entry 'user_01-vid_100'"))
                .when(historyRepository).save(any(WatchHistory.class));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 5, 5, 100);

        assertThat(history.getVid()).isEqualTo("vid_100");
        verify(historyRepository).save(any(WatchHistory.class));
        verify(historyRepository).update(any(WatchHistory.class));
    }
}
