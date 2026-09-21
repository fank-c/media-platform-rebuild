package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.application.dto.RecommendFeedResult;
import com.calles.platform.recommend.config.QdrantProperties;
import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.model.profile.UserVector;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import com.calles.platform.recommend.domain.repository.UserProfileRepository;
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
 * RecommendFeedApplicationService 推荐全流水线单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendFeedApplicationService 推荐流水线测试")
class RecommendFeedApplicationServiceTest {

    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private UserBlockRepository userBlockRepository;
    @Mock
    private CandidateVideoRepository candidateVideoRepository;
    @Mock
    private QdrantClient qdrantClient;
    @Mock
    private QdrantProperties qdrantProperties;

    @InjectMocks
    private RecommendFeedApplicationService recommendFeedApplicationService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(qdrantProperties.getCollectionName()).thenReturn("video_vectors");
    }

    private CandidateVideo createVideo(String id, String videoId, String vid, String authorId,
                                       String domain, String topic, CandidateStatus status) {
        CandidateVideo cv = CandidateVideo.createPublished(id, videoId, vid, authorId, domain, topic, LocalDateTime.now().minusHours(1));
        if (status == CandidateStatus.OFFLINE) {
            cv.markOffline();
        } else if (status == CandidateStatus.BANNED) {
            cv.markBanned();
        }
        return cv;
    }

    @Test
    @DisplayName("冷启动/游客场景：无登录态，直接从最新候选池召回并按新鲜度排序截断")
    void shouldRecommendColdStartForGuest() {
        List<CandidateVideo> activeVideos = List.of(
                createVideo("c1", "v1", "vid_01", "author_1", "tech", "java", CandidateStatus.ACTIVE),
                createVideo("c2", "v2", "vid_02", "author_2", "life", "vlog", CandidateStatus.ACTIVE)
        );
        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(activeVideos);

        RecommendFeedResult result = recommendFeedApplicationService.getPersonalizedFeed(null, 5);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_01");
        assertThat(result.getItems().get(0).getChannel()).isEqualTo("COLD_START");
        assertThat(result.getItems().get(0).getReason()).isEqualTo("新鲜发布");
    }

    @Test
    @DisplayName("向量召回场景：已登录且有向量，优先执行 Qdrant ANN 检索并微调加权")
    void shouldRecommendViaVectorRecallForActiveUser() {
        String userId = "user_100";
        // 构造含非零特征向量的画像
        List<Float> vector = new ArrayList<>(Collections.nCopies(512, 0.1f));
        UserProfile profile = new UserProfile(
                userId,
                UserVector.of(vector),
                new HashMap<>(),
                new HashMap<>(),
                new ArrayList<>(),
                1L,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(userBlockRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // 模拟 Qdrant 检索返回匹配点
        Map<String, Object> payload1 = Map.of("vid", "vid_q1");
        Map<String, Object> payload2 = Map.of("vid", "vid_q2");
        List<ScoredPoint> points = List.of(
                new ScoredPoint("v_q1", 0.92, payload1),
                new ScoredPoint("v_q2", 0.85, payload2)
        );
        when(qdrantClient.searchPoints(eq("video_vectors"), anyList(), anyInt())).thenReturn(points);

        // 模拟从候选库补齐物料信息
        CandidateVideo cv1 = createVideo("cq1", "v_q1", "vid_q1", "author_1", "tech", "spring", CandidateStatus.ACTIVE);
        CandidateVideo cv2 = createVideo("cq2", "v_q2", "vid_q2", "author_2", "tech", "cloud", CandidateStatus.ACTIVE);
        when(candidateVideoRepository.findByVids(anyList())).thenReturn(List.of(cv1, cv2));
        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(Collections.emptyList());

        RecommendFeedResult result = recommendFeedApplicationService.getPersonalizedFeed(userId, 5);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_q1");
        assertThat(result.getItems().get(0).getChannel()).isEqualTo("VECTOR");
        assertThat(result.getItems().get(0).getScore()).isEqualTo(0.92);
    }

    @Test
    @DisplayName("硬过滤门禁：状态非ACTIVE、作者本人、明确拉黑（视频/作者/主题）、近期已看均被剔除")
    void shouldFilterOutInvalidCandidates() {
        String userId = "user_me";
        UserProfile profile = UserProfile.initialize(userId, 512);
        // 模拟近期已看 vid_watched
        profile.recordPositiveConsumption("vid_watched", null, null, null, 0.1);

        // 模拟拉黑：视频 vid_blocked_v, 作者 author_blocked, 主题 tag_blocked_t
        List<UserBlock> blocks = List.of(
                UserBlock.create(userId, BlockType.VIDEO, "vid_blocked_v", "不喜欢"),
                UserBlock.create(userId, BlockType.AUTHOR, "author_blocked", "拉黑作者"),
                UserBlock.create(userId, BlockType.TOPIC, "tag_blocked_t", "不感兴趣")
        );

        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(userBlockRepository.findByUserId(userId)).thenReturn(blocks);

        // 候选物料集合
        CandidateVideo vNormal = createVideo("c1", "v1", "vid_normal", "author_other", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vMine = createVideo("c2", "v2", "vid_mine", "user_me", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vBlockedVid = createVideo("c3", "v3", "vid_blocked_v", "author_x", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vBlockedAuthor = createVideo("c4", "v4", "vid_blocked_a", "author_blocked", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vBlockedTopic = createVideo("c5", "v5", "vid_blocked_t", "author_y", "tech", "tag_blocked_t,tag_other", CandidateStatus.ACTIVE);
        CandidateVideo vWatched = createVideo("c6", "v6", "vid_watched", "author_z", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vOffline = createVideo("c7", "v7", "vid_offline", "author_w", "tech", "java", CandidateStatus.OFFLINE);

        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(
                List.of(vNormal, vMine, vBlockedVid, vBlockedAuthor, vBlockedTopic, vWatched, vOffline)
        );

        RecommendFeedResult result = recommendFeedApplicationService.getPersonalizedFeed(userId, 10);

        // 只有 vNormal 能够穿透所有硬门禁
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_normal");
    }

    @Test
    @DisplayName("粗细标签加权打分：粗领域疲劳抑制打折，细主题偏好累加微调加分")
    void shouldApplyDomainSuppressionAndTopicBonus() {
        String userId = "user_weight";
        UserProfile profile = UserProfile.initialize(userId, 512);

        // 粗领域 tech 连续未消费 5 次 -> 折扣系数 0.6
        for (int i = 0; i < 5; i++) {
            profile.recordDomainExposure("tech", false);
        }

        // 细主题 spring 偏好消费 3 次
        for (int i = 0; i < 3; i++) {
            profile.recordPositiveConsumption("vid_dummy_" + i, null, List.of("spring"), "tech", 0.1);
        }

        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(userBlockRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // 构造两个物料：
        // v1: domain=tech, topic=spring. (受 tech 折扣 0.6，但享 spring 偏好加分 3 * 0.05 = 0.15)
        // v2: domain=life (无折扣 1.0), topic=cooking (无加分 0.0)
        CandidateVideo v1 = createVideo("c1", "v1", "vid_tech", "author_1", "tech", "spring", CandidateStatus.ACTIVE);
        CandidateVideo v2 = createVideo("c2", "v2", "vid_life", "author_2", "life", "cooking", CandidateStatus.ACTIVE);

        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(List.of(v1, v2));

        RecommendFeedResult result = recommendFeedApplicationService.getPersonalizedFeed(userId, 5);

        assertThat(result.getItems()).hasSize(2);
        // v1 包含偏好标签推荐理由
        Optional<com.calles.platform.recommend.application.dto.RecommendItemResult> techItem = result.getItems().stream()
                .filter(i -> i.getVid().equals("vid_tech"))
                .findFirst();
        assertThat(techItem).isPresent();
        assertThat(techItem.get().getReason()).isEqualTo("偏好标签推荐");
    }

    @Test
    @DisplayName("同作者多样性打散：同一作者物料在输出列表中至少间隔 2 个卡片")
    void shouldDeDuplicateSameAuthorWithGapOfTwo() {
        // author_A 发布了 3 篇高质量视频，author_B、author_C、author_D 各 1 篇
        CandidateVideo a1 = createVideo("ca1", "va1", "vid_a1", "author_A", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo a2 = createVideo("ca2", "va2", "vid_a2", "author_A", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo a3 = createVideo("ca3", "va3", "vid_a3", "author_A", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo b1 = createVideo("cb1", "vb1", "vid_b1", "author_B", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo c1 = createVideo("cc1", "vc1", "vid_c1", "author_C", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo d1 = createVideo("cd1", "vd1", "vid_d1", "author_D", "tech", "java", CandidateStatus.ACTIVE);

        // 返回顺序：a1, a2, a3, b1, c1, d1
        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(List.of(a1, a2, a3, b1, c1, d1));

        RecommendFeedResult result = recommendFeedApplicationService.getPersonalizedFeed(null, 6);

        // 验证结果列表中连续两个物料不为同一个 author_A，且距离 >= 3 (间隔 >= 2)
        List<String> resultVids = result.getItems().stream().map(com.calles.platform.recommend.application.dto.RecommendItemResult::getVid).toList();
        // 期望排布：a1 之后不能立即跟 a2，必须先排 b1、c1，之后才能排 a2
        int indexA1 = resultVids.indexOf("vid_a1");
        int indexA2 = resultVids.indexOf("vid_a2");
        assertThat(indexA1).isEqualTo(0);
        assertThat(indexA2).isGreaterThanOrEqualTo(3); // 间隔至少 2 个 (即 index 0, 1, 2 分别是 a1, b1, c1, index 3 才是 a2)
    }

    @Test
    @DisplayName("降级韧性：Qdrant 抛出异常时不阻断推荐，平滑降级冷启动保底池")
    void shouldFallbackGracefullyWhenQdrantFails() {
        String userId = "user_qdrant_fail";
        List<Float> vector = new ArrayList<>(Collections.nCopies(512, 0.2f));
        UserProfile profile = new UserProfile(
                userId,
                UserVector.of(vector),
                new HashMap<>(),
                new HashMap<>(),
                new ArrayList<>(),
                1L,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(userBlockRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // Qdrant 模拟网络超时异常
        when(qdrantClient.searchPoints(anyString(), anyList(), anyInt()))
                .thenThrow(new RuntimeException("Qdrant connection timeout"));

        // 冷启动保底视频
        CandidateVideo fallback = createVideo("c_fb", "v_fb", "vid_fallback", "author_fb", "life", "news", CandidateStatus.ACTIVE);
        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(List.of(fallback));

        RecommendFeedResult result = recommendFeedApplicationService.getPersonalizedFeed(userId, 5);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_fallback");
        assertThat(result.getItems().get(0).getChannel()).isEqualTo("COLD_START");
    }
}
