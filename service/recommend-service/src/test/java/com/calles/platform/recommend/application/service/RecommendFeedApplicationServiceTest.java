package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.application.channel.RecommendRecallChannel;
import com.calles.platform.recommend.application.channel.impl.ExploreRecallChannel;
import com.calles.platform.recommend.application.channel.impl.FollowingRecallChannel;
import com.calles.platform.recommend.application.channel.impl.PersonalizedRecallChannel;
import com.calles.platform.recommend.application.channel.impl.TrendingRecallChannel;
import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;
import com.calles.platform.recommend.application.dto.RecommendFeedResult;
import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import com.calles.platform.recommend.domain.repository.UserProfileRepository;
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
 * RecommendFeedApplicationService 推荐流混合编排单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendFeedApplicationService 推荐编排测试")
class RecommendFeedApplicationServiceTest {

    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private UserBlockRepository userBlockRepository;
    @Mock
    private CandidateVideoRepository candidateVideoRepository;

    @Mock
    private PersonalizedRecallChannel personalizedRecallChannel;
    @Mock
    private ExploreRecallChannel exploreRecallChannel;
    @Mock
    private TrendingRecallChannel trendingRecallChannel;
    @Mock
    private FollowingRecallChannel followingRecallChannel;

    @InjectMocks
    private RecommendFeedApplicationService service;

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
    @DisplayName("冷启动/游客场景：所有个性化通道降级为空时，自动从最新候选池吸收保底")
    void shouldFallbackToColdStartWhenChannelsEmpty() {
        when(personalizedRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());
        when(exploreRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());
        when(trendingRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());
        when(followingRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());

        CandidateVideo cv1 = createVideo("c1", "v1", "vid_01", "author_1", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo cv2 = createVideo("c2", "v2", "vid_02", "author_2", "life", "vlog", CandidateStatus.ACTIVE);
        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(List.of(cv1, cv2));

        RecommendFeedResult result = service.getPersonalizedFeed(null, 5);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_01");
        assertThat(result.getItems().get(0).getChannel()).isEqualTo("COLD_START");
        assertThat(result.getItems().get(0).getReason()).isEqualTo("新鲜发布");
    }

    @Test
    @DisplayName("多路混合编排：按照槽位交织模板混合各路物料，缺省配额自动吸收顶替")
    void shouldBlendMultiChannelSlotsWithFallback() {
        String userId = "user_blend_1";
        UserProfile profile = UserProfile.initialize(userId);

        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(userBlockRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        // 构造核心物料
        CandidateVideo p1 = createVideo("cp1", "vp1", "vid_p1", "author_p1", "tech", "java", CandidateStatus.ACTIVE);
        when(personalizedRecallChannel.recall(any(), anyInt())).thenReturn(List.of(
                new RecalledCandidate(p1, "PERSONALIZED", 0.95, "量身推荐")
        ));

        // 构造探索物料
        CandidateVideo e1 = createVideo("ce1", "ve1", "vid_e1", "author_e1", "games", "moba", CandidateStatus.ACTIVE);
        when(exploreRecallChannel.recall(any(), anyInt())).thenReturn(List.of(
                new RecalledCandidate(e1, "EXPLORE_SIMILAR", 0.75, "相似探索")
        ));

        // 构造热度物料
        CandidateVideo t1 = createVideo("ct1", "vt1", "vid_t1", "author_t1", "news", "daily", CandidateStatus.ACTIVE);
        when(trendingRecallChannel.recall(any(), anyInt())).thenReturn(List.of(
                new RecalledCandidate(t1, "TRENDING", 0.88, "全站热点")
        ));

        // 关注通道缺省为空
        when(followingRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());

        // 候选池补齐
        org.mockito.Mockito.lenient().when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(Collections.emptyList());

        RecommendFeedResult result = service.getPersonalizedFeed(userId, 3);

        assertThat(result.getItems()).hasSize(3);
        List<String> channels = result.getItems().stream().map(com.calles.platform.recommend.application.dto.RecommendItemResult::getChannel).toList();
        // 包含 PERSONALIZED, EXPLORE_SIMILAR, TRENDING
        assertThat(channels).contains("PERSONALIZED", "EXPLORE_SIMILAR", "TRENDING");
    }

    @Test
    @DisplayName("硬过滤门禁：四道门禁（非ACTIVE、自斥、拉黑、近期已看）在混合后统一生效剔除")
    void shouldFilterOutInvalidCandidates() {
        String userId = "user_me";
        UserProfile profile = UserProfile.initialize(userId);
        profile.recordPositiveConsumption("vid_watched", null, null, null, 0.1);

        List<UserBlock> blocks = List.of(
                UserBlock.create(userId, BlockType.VIDEO, "vid_blocked_v", "不喜欢"),
                UserBlock.create(userId, BlockType.AUTHOR, "author_blocked", "拉黑作者"),
                UserBlock.create(userId, BlockType.TOPIC, "tag_blocked_t", "不感兴趣")
        );

        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));
        when(userBlockRepository.findByUserId(userId)).thenReturn(blocks);

        CandidateVideo vNormal = createVideo("c1", "v1", "vid_normal", "author_other", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vMine = createVideo("c2", "v2", "vid_mine", "user_me", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vBlockedVid = createVideo("c3", "v3", "vid_blocked_v", "author_x", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vBlockedAuthor = createVideo("c4", "v4", "vid_blocked_a", "author_blocked", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vBlockedTopic = createVideo("c5", "v5", "vid_blocked_t", "author_y", "tech", "tag_blocked_t", CandidateStatus.ACTIVE);
        CandidateVideo vWatched = createVideo("c6", "v6", "vid_watched", "author_z", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo vOffline = createVideo("c7", "v7", "vid_offline", "author_w", "tech", "java", CandidateStatus.OFFLINE);

        when(personalizedRecallChannel.recall(any(), anyInt())).thenReturn(List.of(
                new RecalledCandidate(vNormal, "PERSONALIZED", 0.9, "推荐"),
                new RecalledCandidate(vMine, "PERSONALIZED", 0.9, "本人作品"),
                new RecalledCandidate(vBlockedVid, "PERSONALIZED", 0.9, "屏蔽视频"),
                new RecalledCandidate(vBlockedAuthor, "PERSONALIZED", 0.9, "屏蔽作者"),
                new RecalledCandidate(vBlockedTopic, "PERSONALIZED", 0.9, "屏蔽主题"),
                new RecalledCandidate(vWatched, "PERSONALIZED", 0.9, "近期已看"),
                new RecalledCandidate(vOffline, "PERSONALIZED", 0.9, "下线视频")
        ));
        when(exploreRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());
        when(trendingRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());
        when(followingRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());
        when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(Collections.emptyList());

        RecommendFeedResult result = service.getPersonalizedFeed(userId, 10);

        // 仅 vNormal 穿透
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_normal");
    }

    @Test
    @DisplayName("同作者打散：同一作者物料在最终输出流中至少间隔 2 个卡片")
    void shouldDeDuplicateSameAuthorWithGapOfTwo() {
        CandidateVideo a1 = createVideo("ca1", "va1", "vid_a1", "author_A", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo a2 = createVideo("ca2", "va2", "vid_a2", "author_A", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo b1 = createVideo("cb1", "vb1", "vid_b1", "author_B", "tech", "java", CandidateStatus.ACTIVE);
        CandidateVideo c1 = createVideo("cc1", "vc1", "vid_c1", "author_C", "tech", "java", CandidateStatus.ACTIVE);

        when(personalizedRecallChannel.recall(any(), anyInt())).thenReturn(List.of(
                new RecalledCandidate(a1, "PERSONALIZED", 0.95, "推荐1"),
                new RecalledCandidate(a2, "PERSONALIZED", 0.94, "推荐2"),
                new RecalledCandidate(b1, "PERSONALIZED", 0.85, "推荐3"),
                new RecalledCandidate(c1, "PERSONALIZED", 0.80, "推荐4")
        ));
        when(exploreRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());
        when(trendingRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());
        when(followingRecallChannel.recall(any(), anyInt())).thenReturn(Collections.emptyList());
        org.mockito.Mockito.lenient().when(candidateVideoRepository.findRecentActive(anyInt())).thenReturn(Collections.emptyList());

        RecommendFeedResult result = service.getPersonalizedFeed(null, 4);

        List<String> resultVids = result.getItems().stream()
                .map(com.calles.platform.recommend.application.dto.RecommendItemResult::getVid)
                .toList();

        // 验证 a1 和 a2 间隔至少 2 个位置
        int indexA1 = resultVids.indexOf("vid_a1");
        int indexA2 = resultVids.indexOf("vid_a2");
        assertThat(indexA1).isEqualTo(0);
        assertThat(indexA2).isGreaterThanOrEqualTo(3);
    }
}
