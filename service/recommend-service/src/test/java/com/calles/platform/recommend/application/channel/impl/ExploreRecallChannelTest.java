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
}
