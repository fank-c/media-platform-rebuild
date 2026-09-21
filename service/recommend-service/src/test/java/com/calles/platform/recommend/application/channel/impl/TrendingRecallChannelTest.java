package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;

import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.FeedbackLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * TrendingRecallChannel 近期高热度通道测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TrendingRecallChannel 热度召回通道测试")
class TrendingRecallChannelTest {

    @Mock
    private FeedbackLogRepository feedbackLogRepository;
    @Mock
    private CandidateVideoRepository candidateVideoRepository;

    @InjectMocks
    private TrendingRecallChannel channel;

    @Test
    @DisplayName("基础属性：通道标识与目标配比为 10%")
    void shouldReturnCorrectMetadata() {
        assertThat(channel.getChannelName()).isEqualTo("TRENDING");
        assertThat(channel.getTargetRatioPercentage()).isEqualTo(10);
    }

    @Test
    @DisplayName("热度事实命中：按近期播放量 Top 召回全站热门卡片")
    void shouldRecallTopTrendingFromLogs() {
        RecallContext context = RecallContext.builder().userId("u_any").build();
        when(feedbackLogRepository.findTopVidsByPlays(any(), anyInt()))
                .thenReturn(List.of("vid_hot_1"));

        CandidateVideo cv = CandidateVideo.createPublished("c1", "v1", "vid_hot_1", "a1", "tech", "ai", LocalDateTime.now());
        when(candidateVideoRepository.findByVids(List.of("vid_hot_1"))).thenReturn(List.of(cv));

        List<RecalledCandidate> result = channel.recall(context, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCandidate().getVid()).isEqualTo("vid_hot_1");
        assertThat(result.get(0).getChannel()).isEqualTo("TRENDING");
        assertThat(result.get(0).getReason()).isEqualTo("全站热点");
    }

    @Test
    @DisplayName("热度事实为空时：平滑回退最新活跃物料作为热门兜底")
    void shouldFallbackToRecentActiveWhenNoLogs() {
        RecallContext context = RecallContext.builder().userId("u_any").build();
        when(feedbackLogRepository.findTopVidsByPlays(any(), anyInt())).thenReturn(Collections.emptyList());

        CandidateVideo cv = CandidateVideo.createPublished("c2", "v2", "vid_recent_1", "a2", "life", "vlog", LocalDateTime.now());
        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(List.of(cv));

        List<RecalledCandidate> result = channel.recall(context, 1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCandidate().getVid()).isEqualTo("vid_recent_1");
        assertThat(result.get(0).getReason()).isEqualTo("热门精选");
    }
}
