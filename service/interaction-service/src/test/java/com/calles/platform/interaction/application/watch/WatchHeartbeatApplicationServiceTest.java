package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

        // 使用本地锁降级实现的 RedisLockService，真实执行锁生命周期
        RedisLockService lockService = new RedisLockService(null);

        service = new WatchHeartbeatApplicationService(
                historyRepository,
                counterRepository,
                eventPublisher,
                lockService,
                transactionTemplate,
                REPEAT_WINDOW,
                VALID_PLAY_THRESHOLD
        );
    }

    @Test
    @DisplayName("0秒初始心跳：纯净新建观看历史记录，绝不累加播放量且绝不发布事件")
    void shouldCreateHistoryWithoutIncrementingViewOrPublishingEventOnInitialZeroSecondHeartbeat() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 0, 0, 100);

        assertThat(history.getLastPosition()).isEqualTo(0);
        assertThat(history.getWatchedDuration()).isEqualTo(0);
        assertThat(history.getLastValidPlayAt()).isNull();
        verify(historyRepository).save(any(WatchHistory.class));
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("心跳增量异常偏大时自动安全截断为15秒上限")
    void shouldTruncateExcessiveDeltaDurationToMaxLimit() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 0, 0, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        // 客户端上报 999 秒大增量（快进或恶意伪造）
        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 20, 999, 100);

        // 验证增量被安全截断为 15 秒，避免快速刷时长
        assertThat(history.getWatchedDuration()).isEqualTo(15);
    }

    @Test
    @DisplayName("心跳累计达标且事务成功提交：通过 afterCommit 触发递增播放量与写入 PLAY 事件")
    void shouldIncrementViewCountAfterCommitWhenThresholdMetAndCasSucceeds() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 3, 3, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.claimValidPlay(eq(existing.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 7, 4, 100);

        assertThat(history.getLastPosition()).isEqualTo(7);
        assertThat(history.getWatchedDuration()).isEqualTo(7);
        assertThat(history.getLastValidPlayAt()).isNotNull();
        verify(historyRepository).update(existing);

        // 验证写入 Outbox 事件
        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY);

        // 验证事务提交后 afterCommit 触发了 Redis 播放量累加
        verify(counterRepository).incrementViewCount("vid_100", 1L);
    }

    @Test
    @DisplayName("写 Outbox 异常导致事务回滚时：afterCommit 不执行，Redis 播放量绝对不被累加")
    void shouldNotIncrementViewCountWhenTransactionRollsBackDueToOutboxFailure() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 3, 3, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.claimValidPlay(eq(existing.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        // 模拟写入 Outbox 事件时抛出数据库或网络异常导致事务回滚
        doThrow(new RuntimeException("Outbox 插入失败，模拟数据库磁盘满或死锁"))
                .when(eventPublisher).publishVideoAction(any());

        assertThatThrownBy(() -> service.processHeartbeat("vid_100", "user_01", 7, 4, 100))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Outbox 插入失败");

        // 关键断言：事务回滚后，afterCommit 绝不执行，Redis 播放量绝不递增（杜绝虚高）
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
    }

    @Test
    @DisplayName("冷却期内重复心跳：CAS抢占返回0，仅更新断点事实，不增加播放量且不写事件")
    void shouldNotIncrementViewCountWithinCooldownWindowWhenCasFails() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 10, 10, 100);
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
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
        existing.markValidPlay(LocalDateTime.now().minusHours(7)); // 7小时前有效播放
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.claimValidPlay(eq(existing.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 25, 5, 100);

        assertThat(history.getLastPosition()).isEqualTo(25);
        verify(historyRepository).update(existing);
        verify(counterRepository).incrementViewCount("vid_100", 1L);

        ArgumentCaptor<VideoActionPayload> captor = ArgumentCaptor.forClass(VideoActionPayload.class);
        verify(eventPublisher).publishVideoAction(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(VideoActionPayload.ACTION_PLAY);
    }

    @Test
    @DisplayName("心跳进度达90%且完播CAS置位成功：标记完播并触发 PLAY_COMPLETE 事件")
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

    @Test
    @DisplayName("用户伪删除历史记录后重播：自愈复活但严格继承原时间戳，冷却期内CAS返回0不刷播放量")
    void shouldReviveSoftDeletedRecordAndPreserveCooldownWindow() {
        WatchHistory existing = WatchHistory.create("user_01", "vid_100", 20, 20, 100);
        existing.markValidPlay(LocalDateTime.now().minusHours(1));
        existing.markDeleted();
        assertThat(existing.isDeleted()).isTrue();

        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));
        when(historyRepository.claimValidPlay(eq(existing.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(0);

        WatchHistory history = service.processHeartbeat("vid_100", "user_01", 25, 5, 100);

        assertThat(history.isDeleted()).isFalse();
        verify(historyRepository).revive(existing);
        verify(counterRepository, never()).incrementViewCount(any(), any(Long.class));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("心跳加锁排队超时：捕获 LockAcquireTimeoutException 并平滑降级为只读返回已有断点")
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
                REPEAT_WINDOW, VALID_PLAY_THRESHOLD
        );

        WatchHistory result = customService.processHeartbeat("vid_100", "user_01", 50, 10, 100);

        assertThat(result.getLastPosition()).isEqualTo(40);
        verify(historyRepository).findByUserAndVid("user_01", "vid_100");
    }

    @Test
    @DisplayName("业务内部抛出运行时异常时：不被锁降级逻辑拦截，原样向上抛出")
    void shouldPropagateBusinessExceptionWhenOccurredInsideTransaction() {
        when(historyRepository.findPhysicalByUserAndVid("user_01", "vid_100"))
                .thenThrow(new IllegalStateException("模拟底层状态校验非法异常"));

        assertThatThrownBy(() -> service.processHeartbeat("vid_100", "user_01", 10, 5, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟底层状态校验非法异常");
    }
}
