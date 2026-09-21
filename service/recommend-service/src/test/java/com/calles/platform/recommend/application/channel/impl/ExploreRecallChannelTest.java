package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;

import com.calles.platform.recommend.config.QdrantProperties;
import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.infrastructure.qdrant.QdrantClient;
import com.calles.platform.recommend.infrastructure.qdrant.dto.QdrantDTOs.ScoredPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * ExploreRecallChannel 探索发现召回通道测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExploreRecallChannel 探索通道测试")
class ExploreRecallChannelTest {

    @Mock
    private QdrantClient qdrantClient;
    @Mock
    private QdrantProperties qdrantProperties;
    @Mock
    private CandidateVideoRepository candidateVideoRepository;

    @InjectMocks
    private ExploreRecallChannel channel;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(qdrantProperties.getCollectionName()).thenReturn("video_vectors");
    }

    @Test
    @DisplayName("基础属性：通道标识与目标配比为 30%")
    void shouldReturnCorrectMetadata() {
        assertThat(channel.getChannelName()).isEqualTo("EXPLORE");
        assertThat(channel.getTargetRatioPercentage()).isEqualTo(30);
    }

    @Test
    @DisplayName("跨领域随机探索：优先采样用户尚未曝光的粗领域物料")
    void shouldSampleCrossDomainCandidates() {
        String userId = "user_exp_1";
        UserProfile profile = UserProfile.initialize(userId);
        // 用户仅在 tech 领域产生过曝光
        profile.recordDomainExposure("tech", true);

        RecallContext context = RecallContext.builder()
                .userId(userId)
                .userProfile(profile)
                .targetTotalSize(10)
                .build();

        // 候选池中包含一个生活领域 life 的物料
        CandidateVideo cvLife = CandidateVideo.createPublished("c_life", "v_life", "vid_life", "author_l", "life", "vlog", LocalDateTime.now());
        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(List.of(cvLife));

        List<RecalledCandidate> result = channel.recall(context, 2);

        assertThat(result).isNotEmpty();
        Optional<RecalledCandidate> randomItem = result.stream()
                .filter(r -> "EXPLORE_RANDOM".equals(r.getChannel()))
                .findFirst();
        assertThat(randomItem).isPresent();
        assertThat(randomItem.get().getCandidate().getVid()).isEqualTo("vid_life");
        assertThat(randomItem.get().getReason()).isEqualTo("探索新领域");
    }

    @Test
    @DisplayName("近似探索：向量结果倒排并按用户活跃粗领域过滤放行")
    void shouldRecallSimilarCandidatesWithReversedPointsAndDomainFilter() {
        String userId = "user_exp_2";
        UserProfile profile = UserProfile.initialize(userId);
        // 用户拥有 tech 领域的活跃画像
        profile.recordDomainExposure("tech", true);
        // 为用户初始化有效的向量
        profile.recordPositiveConsumption("v_seed", List.of(0.1f, 0.2f, 0.3f), List.of("tag1"), "tech", 0.1);

        RecallContext context = RecallContext.builder()
                .userId(userId)
                .userProfile(profile)
                .targetTotalSize(10)
                .build();

        // 模拟 Qdrant 返回 3 个点，余弦相似度由高到低
        ScoredPoint pHigh = new ScoredPoint("p1", 0.95, Map.of("vid", "vid_high"));
        ScoredPoint pMidOtherDomain = new ScoredPoint("p2", 0.75, Map.of("vid", "vid_mid_other"));
        ScoredPoint pLow = new ScoredPoint("p3", 0.60, Map.of("vid", "vid_low"));

        when(qdrantClient.searchPoints(eq("video_vectors"), anyList(), eq(35)))
                .thenReturn(List.of(pHigh, pMidOtherDomain, pLow));

        // 对应候选物料
        CandidateVideo cvHigh = CandidateVideo.createPublished("c1", "v1", "vid_high", "author1", "tech", "java", LocalDateTime.now());
        CandidateVideo cvMidOther = CandidateVideo.createPublished("c2", "v2", "vid_mid_other", "author2", "game", "fps", LocalDateTime.now());
        CandidateVideo cvLow = CandidateVideo.createPublished("c3", "v3", "vid_low", "author3", "tech", "hardware", LocalDateTime.now());

        when(candidateVideoRepository.findByVids(anyList()))
                .thenReturn(List.of(cvHigh, cvMidOther, cvLow));

        // 要求召回 2 条
        List<RecalledCandidate> result = channel.recall(context, 2);

        assertThat(result).isNotEmpty();
        List<RecalledCandidate> similarItems = result.stream()
                .filter(r -> "EXPLORE_SIMILAR".equals(r.getChannel()))
                .toList();

        // 验证：倒排后优先挑选了得分较低的外围弱关联物料 vid_low
        assertThat(similarItems).isNotEmpty();
        assertThat(similarItems.get(0).getCandidate().getVid()).isEqualTo("vid_low");
        assertThat(similarItems.get(0).getReason()).isEqualTo("相似领域探索");

        // 验证：vid_mid_other 因为是 game 领域不在用户活跃 tech 领域中，被成功过滤
        assertThat(similarItems.stream().anyMatch(r -> "vid_mid_other".equals(r.getCandidate().getVid()))).isFalse();
    }

    @Test
    @DisplayName("近似探索：Qdrant 检索异常时平滑降级，不中断探索流水线")
    void shouldFallbackWhenQdrantFails() {
        String userId = "user_exp_3";
        UserProfile profile = UserProfile.initialize(userId);
        profile.recordPositiveConsumption("v_seed", List.of(0.1f, 0.2f), List.of("tag1"), "tech", 0.1);

        RecallContext context = RecallContext.builder()
                .userId(userId)
                .userProfile(profile)
                .targetTotalSize(10)
                .build();

        when(qdrantClient.searchPoints(eq("video_vectors"), anyList(), anyInt()))
                .thenThrow(new RuntimeException("Qdrant 连接超时"));

        CandidateVideo fallbackCv = CandidateVideo.createPublished("c_fb", "v_fb", "vid_fb", "author_fb", "life", "vlog", LocalDateTime.now());
        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(List.of(fallbackCv));

        List<RecalledCandidate> result = channel.recall(context, 2);

        // 即使 Qdrant 失败，后续的跨领域或最新物料仍可平滑兜底
        assertThat(result).isNotEmpty();
    }
}

