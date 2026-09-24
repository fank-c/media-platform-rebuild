package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
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
    private InteractionEventPublisher eventPublisher;

    private WatchHeartbeatApplicationService service;

    private static final Duration REPEAT_WINDOW = Duration.ofHours(6);
    private static final Duration VALID_PLAY_THRESHOLD = Duration.ofSeconds(5);

    @BeforeEach
    void setUp() {
        service = new WatchHeartbeatApplicationService(
                historyRepository,
                counterRepository,
                eventPublisher,
                REPEAT_WINDOW,
                VALID_PLAY_THRESHOLD
        );
    }

    @Test
    @DisplayName("首次心跳时长未达到5秒阈值：仅写入观看历史断点，不增加播放量且不写事件")
    void shouldNotIncrementViewCountWhenInitialHeartbeatBelowThreshold() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 3, 3, 100);

        assertThat(history.getLastPosition()).isEqualTo(3);
        assertThat(history.getWatchedDuration()).isEqualTo(3);
        assertThat(history.getLastValidPlayAt()).isNull();
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("首次心跳即达到5秒阈值：写入历史并设置防重时间戳，累加播放量且发布标准 PLAY 事件")
    void shouldIncrementViewCountWhenInitialHeartbeatMeetsThreshold() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 6, 6, 100);

        assertThat(history.getLastPosition()).isEqualTo(6);
        assertThat(history.getWatchedDuration()).isEqualTo(6);
        assertThat(history.getLastValidPlayAt()).isNotNull();
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository).incrementViewCount("vid_100", 1L);

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY);
        assertThat(captor.getValue().state()).isEqualTo(VideoActionPayload.STATE_ACTIVE);
    }

    @Test
    @DisplayName("心跳累计达到5秒阈值且CAS抢占成功：增加播放量并写入单条 PLAY 事件")
    void shouldIncrementViewCountWhenWatchedDurationReachesThresholdAndCasSucceeds() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 3, 3, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        // CAS 抢占成功
        when(historyRepository.claimValidPlay(eq(existing.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 7, 4, 100);

        assertThat(history.getLastPosition()).isEqualTo(7);
        assertThat(history.getWatchedDuration()).isEqualTo(7);
        assertThat(history.getLastValidPlayAt()).isNotNull();
        verify(historyRepository).update(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY);
        assertThat(captor.getValue().state()).isEqualTo(VideoActionPayload.STATE_ACTIVE);
    }

    @Test
    @DisplayName("冷却期内重复心跳：CAS抢占返回0，仅更新断点事实，不增加播放量且不写事件")
    void shouldNotIncrementViewCountWithinCooldownWindowWhenCasFails() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 10, 10, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        // 处于冷却期内或已被并发处理，CAS 抢占返回 0
        when(historyRepository.claimValidPlay(eq(existing.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(0);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 15, 5, 100);

        assertThat(history.getLastPosition()).isEqualTo(15);
        verify(historyRepository).update(existing);
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("冷却期结束后再次观看达标：CAS再次成功，可重复计数并写入新一条 PLAY 事件")
    void shouldIncrementViewCountAgainWhenCooldownExpiredAndCasSucceeds() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 20, 20, 100);
        existing.markValidPlay(LocalDateTime.now().minusHours(7)); // 7小时前有效播放，已过6小时冷却期
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        // 超出冷却期后 CAS 再次成功抢占
        when(historyRepository.claimValidPlay(eq(existing.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 25, 5, 100);

        assertThat(history.getLastPosition()).isEqualTo(25);
        verify(historyRepository).update(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY);
        assertThat(captor.getValue().state()).isEqualTo(VideoActionPayload.STATE_ACTIVE);
    }

    @Test
    @DisplayName("多端首次并发心跳触发唯一键冲突：自愈重新读取，CAS保证同周期最多只计一次")
    void shouldRecoverGracefullyFromConcurrentFirstHeartbeatInsert() {
        WatchHistory existingFromOtherDevice = WatchHistory.create("user_01", "vid_100", 6, 6, 100);
        existingFromOtherDevice.markValidPlay(LocalDateTime.now()); // 另一台设备先完成插入并成功抢占有效播放

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100"))
                .thenReturn(Optional.empty()) // 首次查空
                .thenReturn(Optional.of(existingFromOtherDevice)); // 冲突后重新查库得到已有记录

        org.mockito.Mockito.doThrow(new DuplicateKeyException("Duplicate entry 'user_01-vid_100'"))
                .when(historyRepository).save(any(WatchHistory.class));
        // 另一设备已占领本轮时间戳，当前设备 CAS 返回 0
        when(historyRepository.claimValidPlay(eq(existingFromOtherDevice.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(0);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 8, 2, 100);

        assertThat(history.getVid()).isEqualTo("vid_100");
        verify(historyRepository).save(any(WatchHistory.class));
        verify(historyRepository).update(existingFromOtherDevice);
        // 绝不重复递增计数与发布事件
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("心跳进度达90%且CAS置位成功：标记完播并触发 PLAY_COMPLETE 事件")
    void shouldTriggerPlayCompleteWhenCasSucceeds() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 80, 80, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.markCompletedIfUncompleted(existing.getId())).thenReturn(1); // 完播 CAS 成功

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 92, 12, 100);

        assertThat(history.isCompleted()).isTrue();
        verify(historyRepository).update(existing);
        verify(historyRepository).markCompletedIfUncompleted(existing.getId());

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY_COMPLETE);
        assertThat(captor.getValue().state()).isEqualTo(VideoActionPayload.STATE_ACTIVE);
    }

    @Test
    @DisplayName("多端并发心跳达90%时：完播CAS仅允许成功一次，防范完播事件双发")
    void shouldPreventDuplicateCompleteEventsWhenCompleteCasReturnsZero() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 85, 85, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.markCompletedIfUncompleted(existing.getId())).thenReturn(0); // 完播 CAS 抢占失败

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 95, 10, 100);

        verify(historyRepository).update(existing);
        verify(historyRepository).markCompletedIfUncompleted(existing.getId());
        verify(eventPublisher, never()).publishVideoAction(any());
    }
}
