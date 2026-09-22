package com.calles.platform.interaction.application.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.domain.model.like.LikeStatus;
import com.calles.platform.interaction.domain.model.like.VideoLike;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.domain.repository.VideoLikeRepository;
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
    private VideoCounterRepository counterRepository;

    private LikeApplicationService service;

    @BeforeEach
    void setUp() {
        service = new LikeApplicationService(likeRepository, counterRepository);
    }

    @Test
    @DisplayName("首次点赞成功并自增计数")
    void shouldLikeVideoSuccessfullyFirstTime() {
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.empty());

        boolean result = service.likeVideo("vid_100", "user_01");

        assertThat(result).isTrue();
        verify(likeRepository).save(any(VideoLike.class));
        verify(counterRepository).adjustLikeCount("vid_100", 1L);
    }

    @Test
    @DisplayName("重复点赞幂等且不重复自增计数")
    void shouldBeIdempotentWhenAlreadyLiked() {
        VideoLike existing = VideoLike.create("vid_100", "user_01");
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        boolean result = service.likeVideo("vid_100", "user_01");

        assertThat(result).isTrue();
        verify(likeRepository, never()).save(any());
        verify(likeRepository, never()).update(any());
        verify(counterRepository, never()).adjustLikeCount(any(), eq(1L));
    }

    @Test
    @DisplayName("已取消点赞后重新激活并递增计数")
    void shouldReactivateLikeWhenPreviouslyCancelled() {
        VideoLike existing = VideoLike.create("vid_100", "user_01");
        existing.cancel();
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        boolean result = service.likeVideo("vid_100", "user_01");

        assertThat(result).isTrue();
        assertThat(existing.getStatus()).isEqualTo(LikeStatus.ACTIVE);
        verify(likeRepository).update(existing);
        verify(counterRepository).adjustLikeCount("vid_100", 1L);
    }

    @Test
    @DisplayName("取消点赞成功并扣减计数")
    void shouldUnlikeVideoSuccessfully() {
        VideoLike existing = VideoLike.create("vid_100", "user_01");
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        boolean result = service.unlikeVideo("vid_100", "user_01");

        assertThat(result).isFalse();
        assertThat(existing.getStatus()).isEqualTo(LikeStatus.CANCELLED);
        verify(likeRepository).update(existing);
        verify(counterRepository).adjustLikeCount("vid_100", -1L);
    }

    @Test
    @DisplayName("重复取消点赞幂等且不重复扣减计数")
    void shouldBeIdempotentWhenAlreadyCancelled() {
        VideoLike existing = VideoLike.create("vid_100", "user_01");
        existing.cancel();
        when(likeRepository.findByUserAndVid("user_01", "vid_100")).thenReturn(Optional.of(existing));

        boolean result = service.unlikeVideo("vid_100", "user_01");

        assertThat(result).isFalse();
        verify(counterRepository, never()).adjustLikeCount(any(), eq(-1L));
    }
}
