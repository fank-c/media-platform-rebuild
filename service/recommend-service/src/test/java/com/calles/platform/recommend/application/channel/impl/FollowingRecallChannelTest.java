package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.client.InteractionStatsClient;
import com.calles.platform.recommend.application.client.UserFollowingClient;
import com.calles.platform.recommend.application.client.dto.FollowingVideoStat;
import com.calles.platform.recommend.application.client.dto.FollowingVideoStatsRequest;
import com.calles.platform.recommend.config.FollowingRecallProperties;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FollowingRecallChannel 关注召回与排序测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FollowingRecallChannel 关注通道测试")
class FollowingRecallChannelTest {

    @Mock
    private UserFollowingClient userFollowingClient;
    @Mock
    private InteractionStatsClient interactionStatsClient;
    @Mock
    private CandidateVideoRepository candidateVideoRepository;

    private FollowingRecallProperties properties;
    private FollowingRecallChannel channel;
    private final LocalDateTime anchor = LocalDateTime.of(2026, 9, 29, 12, 0);

    @BeforeEach
    void setUp() {
        properties = new FollowingRecallProperties();
        channel = new FollowingRecallChannel(
                userFollowingClient,
                interactionStatsClient,
                candidateVideoRepository,
                properties,
                Clock.fixed(anchor.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    @Test
    @DisplayName("游客或非法 count 不访问依赖")
    void shouldShortCircuitUnsupportedRequest() {
        assertThat(channel.recall(RecallContext.builder().build(), 2)).isEmpty();
        assertThat(channel.recall(RecallContext.builder().userId("u1").build(), 0)).isEmpty();
        verify(userFollowingClient, never()).getRecentFollowingIds(any());
    }

    @Test
    @DisplayName("按播放量与时间衰减排序，并限制输出数量")
    void shouldRankByViewsAndFreshness() {
        when(userFollowingClient.getRecentFollowingIds("u1"))
                .thenReturn(ApiResponse.ok(List.of("a1", "a2")));
        CandidateVideo fresh = CandidateVideo.createPublished("c1", "v1", "cv1", "a1", "", "", anchor);
        CandidateVideo old = CandidateVideo.createPublished("c2", "v2", "cv2", "a2", "", "", anchor.minusHours(24));
        when(candidateVideoRepository.findRecentActiveByAuthorIds(any(), any(), any(), any(int.class)))
                .thenReturn(List.of(old, fresh));
        when(interactionStatsClient.getVideoStats(any(FollowingVideoStatsRequest.class)))
                .thenReturn(ApiResponse.ok(Map.of(
                        "cv1", new FollowingVideoStat("cv1", 0L),
                        "cv2", new FollowingVideoStat("cv2", 0L))));

        var result = channel.recall(RecallContext.builder().userId("u1").build(), 2);

        assertThat(result).extracting(item -> item.getCandidate().getVid()).containsExactly("cv1", "cv2");
        assertThat(result.get(0).getScore()).isEqualTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(result.get(1).getScore()).isEqualTo(0.5, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    @DisplayName("关注查询失败时降级为空，不访问候选池")
    void shouldDegradeWhenFollowingLookupFails() {
        when(userFollowingClient.getRecentFollowingIds("u1")).thenThrow(new RuntimeException("timeout"));

        assertThat(channel.recall(RecallContext.builder().userId("u1").build(), 2)).isEmpty();
        verify(candidateVideoRepository, never()).findRecentActiveByAuthorIds(any(), any(), any(), any(int.class));
    }
}
