package com.calles.platform.interaction.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.application.like.LikeApplicationService;
import com.calles.platform.interaction.application.star.StarApplicationService;
import com.calles.platform.interaction.application.watch.WatchProgressApplicationService;
import com.calles.platform.interaction.application.watch.WatchProgressView;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.calles.platform.interaction.domain.model.share.InteractionShareRecord;
import com.calles.platform.interaction.exception.InteractionException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
    private WatchProgressApplicationService watchProgressService;

    @Mock
    private VideoCounterRepository counterRepository;

    @Mock
    private CounterDeltaRepository counterDeltaRepository;

    @Mock
    private com.calles.platform.interaction.domain.repository.InteractionShareRecordRepository shareRecordRepository;

    @Mock
    private com.calles.platform.interaction.application.event.InteractionEventPublisher eventPublisher;

    private InteractionQueryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new InteractionQueryApplicationService(
                likeService,
                starService,
                watchProgressService,
                counterRepository,
                counterDeltaRepository,
                shareRecordRepository,
                eventPublisher
        );
    }

    @Test
    @DisplayName("正确聚合登录用户的互动快照状态")
    void shouldReturnCorrectMyStateForUser() {
        when(likeService.isLiked("vid_100", "user_01")).thenReturn(true);
        when(starService.isStarred("vid_100", "user_01")).thenReturn(false);
        when(watchProgressService.getProgress("vid_100", "user_01"))
                .thenReturn(new WatchProgressView("vid_100", 45, 45, 120, false));

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

    @Test
    @DisplayName("首次分享正常记录幂等条目、自增计数并写入Outbox")
    void shouldRecordShareFirstTime() {
        when(shareRecordRepository.findByUserIdAndIdempotencyKey("user_01", "idem_key_1")).thenReturn(Optional.empty());

        service.recordShare("vid_100", "user_01", "idem_key_1");

        org.mockito.Mockito.verify(shareRecordRepository).save(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(counterDeltaRepository).incrementShareCount("vid_100", "share:user_01:idem_key_1", 1L);
        org.mockito.Mockito.verify(eventPublisher).publishVideoAction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("相同用户相同幂等键重试分享同一视频时不重复自增计数且不发布事件")
    void shouldBeIdempotentOnDuplicateShareKeyForSameVideo() {
        InteractionShareRecord existing =
                InteractionShareRecord.create("idem_key_1", "user_01", "vid_100");
        when(shareRecordRepository.findByUserIdAndIdempotencyKey("user_01", "idem_key_1")).thenReturn(Optional.of(existing));

        service.recordShare("vid_100", "user_01", "idem_key_1");

        org.mockito.Mockito.verify(shareRecordRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(counterDeltaRepository, org.mockito.Mockito.never()).incrementShareCount(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(eventPublisher, org.mockito.Mockito.never()).publishVideoAction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("相同用户复用相同幂等键分享不同视频时抛出409冲突异常")
    void shouldThrowConflictWhenShareKeyReusedForDifferentVideo() {
        InteractionShareRecord existing =
                InteractionShareRecord.create("idem_key_1", "user_01", "vid_100");
        when(shareRecordRepository.findByUserIdAndIdempotencyKey("user_01", "idem_key_1")).thenReturn(Optional.of(existing));

        InteractionException exception = org.junit.jupiter.api.Assertions.assertThrows(
                InteractionException.class,
                () -> service.recordShare("vid_200", "user_01", "idem_key_1"));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getMessage()).isEqualTo("幂等键已被用于其他分享请求");
        org.mockito.Mockito.verify(counterDeltaRepository, org.mockito.Mockito.never()).incrementShareCount(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("不同用户使用相同幂等键互不干扰并正常记录")
    void shouldAllowDifferentUsersWithSameShareKey() {
        when(shareRecordRepository.findByUserIdAndIdempotencyKey("user_02", "idem_key_1")).thenReturn(Optional.empty());

        service.recordShare("vid_200", "user_02", "idem_key_1");

        org.mockito.Mockito.verify(shareRecordRepository).save(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(counterDeltaRepository).incrementShareCount("vid_200", "share:user_02:idem_key_1", 1L);
        org.mockito.Mockito.verify(eventPublisher).publishVideoAction(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("并发插入触发唯一键冲突时能通过当前读(FOR UPDATE)穿透快照判定幂等命中")
    void shouldHandleConcurrentDuplicateKeyGracefullyWithCurrentRead() {
        InteractionShareRecord existing =
                InteractionShareRecord.create("idem_key_1", "user_01", "vid_100");
        when(shareRecordRepository.findByUserIdAndIdempotencyKey("user_01", "idem_key_1"))
                .thenReturn(Optional.empty());
        when(shareRecordRepository.findByUserIdAndIdempotencyKeyForUpdate("user_01", "idem_key_1"))
                .thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk conflict"))
                .when(shareRecordRepository).save(org.mockito.ArgumentMatchers.any());

        service.recordShare("vid_100", "user_01", "idem_key_1");

        org.mockito.Mockito.verify(shareRecordRepository).findByUserIdAndIdempotencyKeyForUpdate("user_01", "idem_key_1");
        org.mockito.Mockito.verify(counterDeltaRepository, org.mockito.Mockito.never()).incrementShareCount(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("并发插入触发唯一键冲突且当前读查出不同视频时抛出409")
    void shouldThrowConflictWhenConcurrentDuplicateKeyHasDifferentVideo() {
        InteractionShareRecord existing =
                InteractionShareRecord.create("idem_key_1", "user_01", "vid_100");
        when(shareRecordRepository.findByUserIdAndIdempotencyKey("user_01", "idem_key_1"))
                .thenReturn(Optional.empty());
        when(shareRecordRepository.findByUserIdAndIdempotencyKeyForUpdate("user_01", "idem_key_1"))
                .thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk conflict"))
                .when(shareRecordRepository).save(org.mockito.ArgumentMatchers.any());

        InteractionException exception = org.junit.jupiter.api.Assertions.assertThrows(
                InteractionException.class,
                () -> service.recordShare("vid_200", "user_01", "idem_key_1"));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getMessage()).isEqualTo("幂等键已被用于其他分享请求");
        org.mockito.Mockito.verify(shareRecordRepository).findByUserIdAndIdempotencyKeyForUpdate("user_01", "idem_key_1");
    }

    @Test
    @DisplayName("并发插入触发唯一键冲突且当前读依然为空时重抛DuplicateKeyException")
    void shouldRethrowWhenCurrentReadStillEmpty() {
        when(shareRecordRepository.findByUserIdAndIdempotencyKey("user_01", "idem_key_1"))
                .thenReturn(Optional.empty());
        when(shareRecordRepository.findByUserIdAndIdempotencyKeyForUpdate("user_01", "idem_key_1"))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk conflict"))
                .when(shareRecordRepository).save(org.mockito.ArgumentMatchers.any());

        org.junit.jupiter.api.Assertions.assertThrows(
                DuplicateKeyException.class,
                () -> service.recordShare("vid_100", "user_01", "idem_key_1"));

        org.mockito.Mockito.verify(shareRecordRepository).findByUserIdAndIdempotencyKeyForUpdate("user_01", "idem_key_1");
    }
}
