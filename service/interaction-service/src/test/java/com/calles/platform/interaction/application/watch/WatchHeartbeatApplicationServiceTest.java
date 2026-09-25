package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.domain.repository.WatchHistoryRepository;
import com.calles.platform.interaction.exception.LockAcquireTimeoutException;
import com.calles.platform.interaction.infrastructure.redis.RedisLockService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 视频观看心跳、多会话隔离与播放资格流转单元测试。
 */
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
    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);

    @BeforeEach
    void setUp() {
        // 使用支持完整事务生命周期（包含 afterCommit 触发）的测试事务管理器
        AbstractPlatformTransactionManager transactionManager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        // 使用本地锁兜底实现的 RedisLockService，真实执行外层锁生命周期
        RedisLockService lockService = new RedisLockService(null);

        service = new WatchHeartbeatApplicationService(
                historyRepository,
                counterRepository,
                eventPublisher,
                lockService,
                transactionTemplate,
                REPEAT_WINDOW,
                VALID_PLAY_THRESHOLD,
                SESSION_TIMEOUT
        );
    }

    @Test
    @DisplayName("0秒初始心跳：纯净新建观看历史记录，绝不累加播放量且绝不发布事件")
    void shouldCreateHistoryWithoutIncrementingViewOrPublishingEventOnInitialZeroSecondHeartbeat() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 0, 0, 100);

        assertThat(history.getLastPosition()).isEqualTo(0);
        assertThat(history.getWatchedDuration()).isEqualTo(0);
        assertThat(history.getSessionWatchedDuration()).isEqualTo(0);
        assertThat(history.isSessionPlayEmitted()).isFalse();
        assertThat(history.getLastValidPlayAt()).isNull();
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("首次请求已携带有效增量(delta=5)：允许在新建历史同一事务中触发首次播放事件")
    void shouldTriggerInitialPlayWhenFirstHeartbeatAlreadyCarriesValidDelta() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());
        when(historyRepository.claimInitialPlay(any(), any(LocalDateTime.class), eq(5))).thenReturn(1);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 5, 5, 100);

        assertThat(history.getSessionWatchedDuration()).isEqualTo(5);
        assertThat(history.isSessionPlayEmitted()).isTrue();
        assertThat(history.getLastValidPlayAt()).isNotNull();
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository).incrementViewCount("vid_100", 1L);

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY);
    }

    @Test
    @DisplayName("1. 首次会话达到5秒：CAS成功抢占首次播放，发送一次PLAY事件且session_play_emitted置1")
    void shouldEmitPlayOnceWhenFirstSessionReachesFiveSeconds() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 3, 3, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.claimInitialPlay(eq(existing.getId()), any(LocalDateTime.class), eq(5))).thenReturn(1);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 7, 4, 100);

        assertThat(history.getSessionWatchedDuration()).isEqualTo(7);
        assertThat(history.isSessionPlayEmitted()).isTrue();
        assertThat(history.getLastValidPlayAt()).isNotNull();
        verify(historyRepository).update(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY);
    }

    @Test
    @DisplayName("2. 同一会话继续心跳：即使有效时长超过5秒，单会话绝不重复发送第二次PLAY")
    void shouldNotEmitPlayAgainInSameSessionEvenIfWatchedDurationIncreases() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 5, 5, 100);
        existing.markValidPlay(LocalDateTime.now().minusMinutes(2));
        existing.markSessionPlayEmitted(); // 当前会话已发送过 PLAY

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 10, 5, 100);

        assertThat(history.getSessionWatchedDuration()).isEqualTo(10);
        assertThat(history.isSessionPlayEmitted()).isTrue();
        verify(historyRepository).update(existing);
        verify(historyRepository, never()).claimInitialPlay(any(), any(), anyInt());
        verify(historyRepository, never()).claimRepeatPlay(any(), any(), any(), anyInt());
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("3. 同一会话达到30%：置位eligible_for_next_play=1，为下一会话赋予资格，但不发送第二次PLAY")
    void shouldSetEligibleForNextPlayWhenReachingThirtyPercentInSameSessionWithoutDuplicatePlay() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 25, 25, 100);
        existing.markValidPlay(LocalDateTime.now().minusMinutes(5));
        existing.markSessionPlayEmitted(); // 已经在5秒时发送了 PLAY

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        // 本次心跳增加 10 秒，当前会话达到 35 秒 (>= 100 * 30%)
        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 35, 10, 100);

        assertThat(history.getSessionWatchedDuration()).isEqualTo(35);
        assertThat(history.isEligibleForNextPlay()).isTrue();
        verify(historyRepository).update(existing);
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("4. 同一会话只看5秒后离开：超时后新会话开启，上一会话未达30%导致新会话eligible_for_next_play=0")
    void shouldClearEligibleForNextPlayWhenPreviousSessionEndedWithOnlyFiveSeconds() {
        // 会话1在40分钟前（已超时），当时累计观看5秒并发送了PLAY，但未达30%
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 5, 5, 100);
        existing.markValidPlay(LocalDateTime.now().minusMinutes(40));
        existing.markSessionPlayEmitted();
        // 模拟 40 分钟前的心跳时间
        existing.recordHeartbeat(5, 0, 100);
        // 使用反射或调整历史心跳时间模拟超时
        setLastWatchAt(existing, LocalDateTime.now().minusMinutes(40));

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        // 会话2开启，上报 2 秒心跳
        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 7, 2, 100);

        assertThat(history.getSessionWatchedDuration()).isEqualTo(2);
        assertThat(history.isSessionPlayEmitted()).isFalse();
        // 上一会话 5 秒 < 30 秒，新会话不具备播放资格
        assertThat(history.isEligibleForNextPlay()).isFalse();
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("5. 新会话达到5秒但旧会话未达到30%：未获得播放资格，绝不发送PLAY事件")
    void shouldNotEmitPlayWhenNewSessionReachesFiveSecondsIfPreviousSessionUnqualified() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 5, 5, 100);
        existing.markValidPlay(LocalDateTime.now().minusHours(7)); // 距离上次播放已过7小时（超过6小时冷却）
        existing.markSessionPlayEmitted();
        setLastWatchAt(existing, LocalDateTime.now().minusHours(1)); // 会话已超时（1小时前离开）

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        // 新会话上报 5 秒有效观看
        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 10, 5, 100);

        assertThat(history.getSessionWatchedDuration()).isEqualTo(5);
        assertThat(history.isEligibleForNextPlay()).isFalse();
        verify(historyRepository, never()).claimRepeatPlay(any(), any(), any(), anyInt());
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("6. 新会话达到5秒且旧会话达到30%且超过冷却期：CAS成功抢占并发送新的PLAY事件")
    void shouldEmitNewPlayWhenNewSessionReachesFiveSecondsAndPreviousSessionQualifiedAndCooldownPassed() {
        // 旧会话达到 40 秒 (>= 30%)，且距离上次有效播放已过去 7 小时 (超过 6 小时冷却)
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 40, 40, 100);
        existing.markValidPlay(LocalDateTime.now().minusHours(7));
        existing.markSessionPlayEmitted();
        existing.markEligibleForNextPlay();
        setLastWatchAt(existing, LocalDateTime.now().minusHours(1)); // 1小时前离开，超出30m会话超时

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.claimRepeatPlay(eq(existing.getId()), any(LocalDateTime.class), any(LocalDateTime.class), eq(5)))
                .thenReturn(1);

        // 新会话当前心跳上报 5 秒
        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 45, 5, 100);

        assertThat(history.getSessionWatchedDuration()).isEqualTo(5);
        assertThat(history.isSessionPlayEmitted()).isTrue();
        verify(historyRepository).update(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY);
    }

    @Test
    @DisplayName("7. 新会话达到5秒且旧会话达到30%，但仍在冷却期内(如2小时)：不发送PLAY事件")
    void shouldNotEmitPlayWhenStillInCooldownWindowEvenIfPreviousSessionQualified() {
        // 旧会话达到 40 秒 (>= 30%)，但距离上次播放仅过去 2 小时 (未满 6 小时冷却)
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 40, 40, 100);
        existing.markValidPlay(LocalDateTime.now().minusHours(2));
        existing.markSessionPlayEmitted();
        existing.markEligibleForNextPlay();
        setLastWatchAt(existing, LocalDateTime.now().minusMinutes(45)); // 会话已超时

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 45, 5, 100);

        assertThat(history.getSessionWatchedDuration()).isEqualTo(5);
        verify(historyRepository, never()).claimRepeatPlay(any(), any(), any(), anyInt());
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("8. 新会话旧会话合格且已超冷却期，但新会话只看了3秒未达门槛：不发送PLAY")
    void shouldNotEmitPlayWhenNewSessionHasNotReachedFiveSecondsThreshold() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 40, 40, 100);
        existing.markValidPlay(LocalDateTime.now().minusHours(7));
        existing.markSessionPlayEmitted();
        existing.markEligibleForNextPlay();
        setLastWatchAt(existing, LocalDateTime.now().minusHours(1));

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 43, 3, 100);

        assertThat(history.getSessionWatchedDuration()).isEqualTo(3);
        verify(historyRepository, never()).claimRepeatPlay(any(), any(), any(), anyInt());
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("9. 10. watched_duration持续累计，session_watched_duration按会话独立重置")
    void shouldAccumulateWatchedDurationAcrossSessionsAndResetSessionWatchedDuration() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 30, 30, 100);
        setLastWatchAt(existing, LocalDateTime.now().minusHours(1)); // 旧会话结束

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 34, 4, 100);

        // 历史累计总时长 30 + 4 = 34
        assertThat(history.getWatchedDuration()).isEqualTo(34);
        // 当前会话独立累计 0 + 4 = 4
        assertThat(history.getSessionWatchedDuration()).isEqualTo(4);
    }

    @Test
    @DisplayName("11. 12. 逻辑删除复活时：持续累计历史时长，保留原last_valid_play_at时间戳，且不错误清除下一次播放资格")
    void shouldPreserveCooldownAndNextPlayEligibilityWhenRevivingSoftDeletedRecord() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 35, 35, 100);
        LocalDateTime validTime = LocalDateTime.now().minusHours(1);
        existing.markValidPlay(validTime);
        existing.markEligibleForNextPlay(); // 具备资格
        existing.markDeleted();
        assertThat(existing.isDeleted()).isTrue();

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 40, 5, 100);

        assertThat(history.isDeleted()).isFalse();
        assertThat(history.getWatchedDuration()).isEqualTo(40); // 35 + 5
        assertThat(history.getLastValidPlayAt()).isEqualTo(validTime); // 严格保留冷却时间
        assertThat(history.isEligibleForNextPlay()).isTrue(); // 不丢失资格
        verify(historyRepository).revive(existing);
        // 处于冷却期内，不递增播放量
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("13. 心跳增量异常偏大时自动安全截断为15秒上限")
    void shouldTruncateExcessiveDeltaDurationToMaxLimit() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 0, 0, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 20, 999, 100);

        assertThat(history.getWatchedDuration()).isEqualTo(15);
        assertThat(history.getSessionWatchedDuration()).isEqualTo(15);
    }

    @Test
    @DisplayName("14. position超过视频总时长时自动安全截断为视频总时长")
    void shouldTruncatePositionToVideoDurationWhenExceeds() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 0, 0, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        // 上报播放头 150 秒（超过视频总时长 100 秒）
        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 150, 5, 100);

        assertThat(history.getLastPosition()).isEqualTo(100);
    }

    @Test
    @DisplayName("15. 写 Outbox 异常导致事务回滚时：afterCommit 不执行，Redis 播放量绝对不被累加")
    void shouldNotIncrementViewCountWhenTransactionRollsBackDueToOutboxFailure() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 3, 3, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.claimInitialPlay(eq(existing.getId()), any(LocalDateTime.class), eq(5))).thenReturn(1);

        doThrow(new RuntimeException("Outbox 插入失败，模拟数据库磁盘满或死锁"))
                .when(eventPublisher).publishVideoAction(any());

        assertThatThrownBy(() -> service.processHeartbeat("vid_100", "user_01", 7, 4, 100))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Outbox 插入失败");

        // 关键断言：事务回滚后，afterCommit 绝不执行，Redis 播放量绝不递增
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
    }

    @Test
    @DisplayName("16. 心跳加锁排队超时：捕获 LockAcquireTimeoutException 并平滑降级为只读返回已有断点")
    void shouldDegradeToReadOnlyWhenLockAcquireTimeoutExceptionOccurs() {
        RedisLockService mockLock = mock(RedisLockService.class);
        when(mockLock.executeWithLock(any(), any(), any(Supplier.class)))
                .thenThrow(new LockAcquireTimeoutException("int:lock:watch:user_01:vid_100", Duration.ofSeconds(3)));

        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 40, 40, 100);
        when(historyRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        AbstractPlatformTransactionManager tm = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() { return new Object(); }
            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {}
            @Override
            protected void doCommit(DefaultTransactionStatus status) {}
            @Override
            protected void doRollback(DefaultTransactionStatus status) {}
        };
        WatchHeartbeatApplicationService customService = new WatchHeartbeatApplicationService(
                historyRepository, counterRepository, eventPublisher, mockLock,
                new TransactionTemplate(tm),
                REPEAT_WINDOW, VALID_PLAY_THRESHOLD, SESSION_TIMEOUT
        );

        WatchHistory result = customService.processHeartbeat("vid_100", "user_01", 50, 10, 100);

        assertThat(result.getLastPosition()).isEqualTo(40);
        verify(historyRepository).findByUserAndVid("user_01", "vid_100");
    }

    @Test
    @DisplayName("17. 业务内部抛出运行时异常时：不被锁降级逻辑拦截，原样向上抛出")
    void shouldPropagateBusinessExceptionWhenOccurredInsideTransaction() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100"))
                .thenThrow(new IllegalStateException("模拟底层状态校验非法异常"));

        assertThatThrownBy(() -> service.processHeartbeat("vid_100", "user_01", 10, 5, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟底层状态校验非法异常");
    }

    @Test
    @DisplayName("心跳进度达90%且完播CAS置位成功：标记完播并触发 PLAY_COMPLETE 事件")
    void shouldTriggerPlayCompleteWhenCasSucceeds() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 80, 80, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.markCompletedIfUncompleted(existing.getId())).thenReturn(1);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 92, 12, 100);

        assertThat(history.isCompleted()).isTrue();
        verify(historyRepository).update(existing);
        verify(historyRepository).markCompletedIfUncompleted(existing.getId());

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY_COMPLETE);
    }

    @Test
    @DisplayName("首次有效播放同时达到完播阈值时：按PLAY再PLAY_COMPLETE顺序各发布一次")
    void shouldPublishPlayThenCompleteWhenOneHeartbeatMeetsBothThresholds() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());
        when(historyRepository.claimInitialPlay(any(), any(LocalDateTime.class), eq(5))).thenReturn(1);
        when(historyRepository.markCompletedIfUncompleted(any())).thenReturn(1);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 90, 5, 100);

        assertThat(history.isSessionPlayEmitted()).isTrue();
        assertThat(history.isCompleted()).isTrue();
        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishVideoAction(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(VideoActionPayload::action)
                .containsExactly(VideoActionPayload.ACTION_PLAY, VideoActionPayload.ACTION_PLAY_COMPLETE);
        verify(counterRepository).incrementViewCount("vid_100", 1L);
    }

    @Test
    @DisplayName("完播CAS返回0时：不重复发布完播事件")
    void shouldPreventDuplicateCompleteEventsWhenCompleteCasReturnsZero() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 85, 85, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.markCompletedIfUncompleted(existing.getId())).thenReturn(0);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 95, 10, 100);

        verify(historyRepository).update(existing);
        verify(historyRepository).markCompletedIfUncompleted(existing.getId());
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    /**
     * 通过反射辅助设置实体的 lastWatchAt 时间戳（用于精准模拟会话超时）。
     */
    private void setLastWatchAt(WatchHistory history, LocalDateTime lastWatchAt) {
        try {
            java.lang.reflect.Field field = WatchHistory.class.getDeclaredField("lastWatchAt");
            field.setAccessible(true);
            field.set(history, lastWatchAt);
        } catch (Exception e) {
            throw new RuntimeException("反射设置 lastWatchAt 失败", e);
        }
    }
}
