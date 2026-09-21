package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;

import com.calles.platform.recommend.config.QdrantProperties;
import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.model.profile.UserVector;
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
 * PersonalizedRecallChannel 核心个性化召回通道测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalizedRecallChannel 个性化召回通道测试")
class PersonalizedRecallChannelTest {

    @Mock
    private QdrantClient qdrantClient;
    @Mock
    private QdrantProperties qdrantProperties;
    @Mock
    private CandidateVideoRepository candidateVideoRepository;

    @InjectMocks
    private PersonalizedRecallChannel channel;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(qdrantProperties.getCollectionName()).thenReturn("video_vectors");
    }

    @Test
    @DisplayName("基础属性：通道标识与目标配比为 50%")
    void shouldReturnCorrectMetadata() {
        assertThat(channel.getChannelName()).isEqualTo("PERSONALIZED");
        assertThat(channel.getTargetRatioPercentage()).isEqualTo(50);
    }

    @Test
    @DisplayName("游客/无画像时：优雅返回空列表")
    void shouldReturnEmptyForGuestOrEmptyProfile() {
        RecallContext guestContext = RecallContext.builder().userId(null).build();
        assertThat(channel.recall(guestContext, 5)).isEmpty();

        RecallContext emptyProfileContext = RecallContext.builder()
                .userId("u1")
                .userProfile(UserProfile.initialize("u1"))
                .build();
        assertThat(channel.recall(emptyProfileContext, 5)).isEmpty();
    }

    @Test
    @DisplayName("有有效特征向量时：Qdrant ANN 检索命中并进行粗细微调打分")
    void shouldRecallAndScoreWithProfile() {
        String userId = "user_p1";
        List<Float> vector = new ArrayList<>(Collections.nCopies(1024, 0.2f));
        UserProfile profile = UserProfile.initialize(userId);
        profile.recordPositiveConsumption("dummy", vector, List.of("java"), "tech", 0.1);

        RecallContext context = RecallContext.builder()
                .userId(userId)
                .userProfile(profile)
                .targetTotalSize(10)
                .build();

        ScoredPoint point = new ScoredPoint("v_id_1", 0.88, Map.of("vid", "vid_p1"));
        when(qdrantClient.searchPoints(eq("video_vectors"), anyList(), anyInt()))
                .thenReturn(List.of(point));

        CandidateVideo cv = CandidateVideo.createPublished("c1", "v_id_1", "vid_p1", "author_1", "tech", "java", LocalDateTime.now());
        when(candidateVideoRepository.findByVids(List.of("vid_p1"))).thenReturn(List.of(cv));

        List<RecalledCandidate> result = channel.recall(context, 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCandidate().getVid()).isEqualTo("vid_p1");
        assertThat(result.get(0).getChannel()).isEqualTo("PERSONALIZED");
        assertThat(result.get(0).getReason()).isEqualTo("偏好标签推荐");
    }
}
