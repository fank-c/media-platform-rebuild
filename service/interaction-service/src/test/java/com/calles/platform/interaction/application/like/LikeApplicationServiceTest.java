package com.calles.platform.interaction.application.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.like.LikeStatus;
import com.calles.platform.interaction.domain.model.like.VideoLike;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.VideoLikeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LikeApplicationServiceTest {

    @Mock
    private VideoLikeRepository likeRepository;

    @Mock
    private CounterDeltaRepository counterDeltaRepository;

    @Mock
    private com.calles.platform.interaction.application.event.InteractionEventPublisher eventPublisher;

    private LikeApplicationService service;

    @BeforeEach
    void setUp() {
        service = new LikeApplicationService(likeRepository, counterDeltaRepository, eventPublisher);
    }

    @Test
    @DisplayName("首次点赞成功并自增计数")
    void shouldLikeVideoSuccessfullyFirstTime() {
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        boolean result = service.likeVideo("vid_100", "user_01");

        assertThat(result).isTrue();
        org.mockito.ArgumentCaptor<VideoLike> captor = org.mockito.ArgumentCaptor.forClass(VideoLike.class);
        verify(likeRepository).save(captor.capture());
        verify(counterDeltaRepository).adjustLikeCount("vid_100", "like:" + captor.getValue().getId() + ":v1", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("重复点赞幂等且不重复自增计数与发布事件")
    void shouldBeIdempotentWhenAlreadyLiked() {
        VideoLike existing = VideoLike.create("vid_100", "user_01");
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        boolean result = service.likeVideo("vid_100", "user_01");

        assertThat(result).isTrue();
        verify(likeRepository, never()).save(any());
        verify(likeRepository, never()).update(any());
        verify(counterDeltaRepository, never()).adjustLikeCount(any(), any(), eq(1L));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("已取消点赞后重新激活并递增计数与发布事件")
    void shouldReactivateLikeWhenPreviouslyCancelled() {
        VideoLike existing = VideoLike.create("vid_100", "user_01");
        existing.cancel(); // version 从 1 变成 2
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        boolean result = service.likeVideo("vid_100", "user_01"); // reactivate -> version 变成 3

        assertThat(result).isTrue();
        assertThat(existing.getStatus()).isEqualTo(LikeStatus.ACTIVE);
        assertThat(existing.getVersion()).isEqualTo(3L);
        verify(likeRepository).update(existing);
        verify(counterDeltaRepository).adjustLikeCount("vid_100", "like:" + existing.getId() + ":v3", 1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("取消点赞成功并扣减计数与发布事件")
    void shouldUnlikeVideoSuccessfully() {
        VideoLike existing = VideoLike.create("vid_100", "user_01");
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        boolean result = service.unlikeVideo("vid_100", "user_01"); // cancel -> version 变成 2

        assertThat(result).isFalse();
        assertThat(existing.getStatus()).isEqualTo(LikeStatus.CANCELLED);
        assertThat(existing.getVersion()).isEqualTo(2L);
        verify(likeRepository).update(existing);
        verify(counterDeltaRepository).adjustLikeCount("vid_100", "like:" + existing.getId() + ":v2", -1L);
        verify(eventPublisher).publishVideoAction(any());
    }

    @Test
    @DisplayName("重复取消点赞幂等且不重复扣减计数与发布事件")
    void shouldBeIdempotentWhenAlreadyCancelled() {
        VideoLike existing = VideoLike.create("vid_100", "user_01");
        existing.cancel();
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        boolean result = service.unlikeVideo("vid_100", "user_01");

        assertThat(result).isFalse();
        verify(counterDeltaRepository, never()).adjustLikeCount(any(), any(), eq(-1L));
        verify(eventPublisher, never()).publishVideoAction(any());
    }

    @Test
    @DisplayName("点赞 -> 取消 -> 再次点赞：每次状态反转版本单调递增且产生互不冲突的事实来源标识")
    void shouldIncrementVersionOnRoundtripStateChanges() {
        VideoLike like = VideoLike.create("vid_100", "user_01");
        assertThat(like.getVersion()).isEqualTo(1L);

        boolean cancelled = like.cancel();
        assertThat(cancelled).isTrue();
        assertThat(like.getVersion()).isEqualTo(2L);

        boolean reactivated = like.reactivate();
        assertThat(reactivated).isTrue();
        assertThat(like.getVersion()).isEqualTo(3L);
        assertThat(like.getCreatedAt()).isNotNull();

        // 重复 reactivate 幂等，版本不增加
        assertThat(like.reactivate()).isFalse();
        assertThat(like.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("分页查询点赞列表：安全处理边界值并按计算后的 offset 和 limit 检索")
    void shouldQueryLikedVideosWithBoundaryProtection() {
        VideoLike like = VideoLike.create("vid_01", "user_01");
        // 当传入 page=-1, size=200 时，经过防御修正应为 page=1, size=100, offset=0, limit=100
        when(likeRepository.findActivePageByUserId("user_01", 0, 100)).thenReturn(List.of(like));

        List<VideoLike> result = service.getLikedVideos("user_01", -1, 200);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVid()).isEqualTo("vid_01");
        verify(likeRepository).findActivePageByUserId("user_01", 0, 100);
    }

    @Test
    @DisplayName("超大页码乘积溢出整型上限时直接返回空列表且不查仓储")
    void shouldReturnEmptyListWhenPageCausesIntegerOverflow() {
        // page = 214748365, size = 20 时，若用 int 计算会发生溢出变成 -16
        List<VideoLike> result = service.getLikedVideos("user_01", 214748365, 20);

        assertThat(result).isEmpty();
        verify(likeRepository, never()).findActivePageByUserId(any(), any(int.class), any(int.class));
    }

    @Test
    @DisplayName("用户 ID 为空时直接返回空列表且不查仓储")
    void shouldReturnEmptyWhenUserIdIsBlank() {
        List<VideoLike> result = service.getLikedVideos("  ", 1, 20);

        assertThat(result).isEmpty();
        verify(likeRepository, never()).findActivePageByUserId(any(), any(int.class), any(int.class));
    }
}
