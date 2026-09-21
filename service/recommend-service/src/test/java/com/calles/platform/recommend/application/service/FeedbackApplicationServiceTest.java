package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.VideoVector;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.FeedbackLogRepository;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import com.calles.platform.recommend.domain.repository.UserProfileRepository;
import com.calles.platform.recommend.domain.repository.VideoVectorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * FeedbackApplicationService 行为反馈与画像演进单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackApplicationService 行为反馈测试")
class FeedbackApplicationServiceTest {

    @Mock
    private FeedbackLogRepository feedbackLogRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private UserBlockRepository userBlockRepository;
    @Mock
    private CandidateVideoRepository candidateVideoRepository;
    @Mock
    private VideoVectorRepository videoVectorRepository;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private FeedbackApplicationService feedbackApplicationService;

    private CandidateVideo mockCandidate(String vid, String authorId, String domain, String topic) {
        return CandidateVideo.createPublished("c1", "video_id_1", vid, authorId, domain, topic, LocalDateTime.now());
    }

    @Test
    @DisplayName("有效完播消费：触发不可篡改流水记录，并驱动画像 EMA 向量合入与细主题加权")
    void shouldRecordPositiveConsumptionAndDriveProfileEvolution() {
        String userId = "user_play";
        String vid = "vid_play_01";
        CandidateVideo candidate = mockCandidate(vid, "author_x", "tech", "java,spring");
        when(candidateVideoRepository.findByVid(vid)).thenReturn(Optional.of(candidate));

        UserProfile profile = UserProfile.initialize(userId, 512);
        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));

        // 模拟特征向量存在
        VideoVector videoVector = VideoVector.init("video_id_1", vid);
        videoVector.markCompleted("test-model", 512, "[0.1, 0.2]", true);
        when(videoVectorRepository.findByVid(vid)).thenReturn(Optional.of(videoVector));

        // 播放 25 秒，总长 30 秒 (完播率 > 30%)
        feedbackApplicationService.recordFeedback(
                userId, vid, FeedbackActionType.PLAY, 25, 30, null, "trace_play", LocalDateTime.now()
        );

        // 验证流水存证落库
        verify(feedbackLogRepository).save(argThat(log ->
                log.getUserId().equals(userId) && log.getVid().equals(vid) && log.getActionType() == FeedbackActionType.PLAY
        ));

        // 验证画像保存
        verify(userProfileRepository).saveOrUpdate(argThat(p ->
                p.hasWatchedRecently(vid) && p.getTopicScore("java") > 0
        ));
    }

    @Test
    @DisplayName("快速跳过滑过：触发流水记录，并在画像中累积粗领域曝光未消费计数")
    void shouldRecordDomainSuppressionOnSkip() {
        String userId = "user_skip";
        String vid = "vid_skip_01";
        CandidateVideo candidate = mockCandidate(vid, "author_y", "games", "moba");
        when(candidateVideoRepository.findByVid(vid)).thenReturn(Optional.of(candidate));

        UserProfile profile = UserProfile.initialize(userId, 512);
        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));

        // 播放 1 秒，总长 60 秒 (完播率 < 10% 且时长 < 3s)
        feedbackApplicationService.recordFeedback(
                userId, vid, FeedbackActionType.SKIP, 1, 60, null, "trace_skip", LocalDateTime.now()
        );

        verify(feedbackLogRepository).save(any());
        verify(userProfileRepository).saveOrUpdate(argThat(p ->
                p.getDomainStates().containsKey("games") && p.getDomainStates().get("games").getExposureCount() == 1
        ));
    }

    @Test
    @DisplayName("主动负反馈拉黑作者：自动沉淀为作者屏蔽黑名单，并抑制粗领域")
    void shouldRecordDislikeAndCreateAuthorBlock() {
        String userId = "user_dislike";
        String vid = "vid_dislike_01";
        String authorId = "author_bad";
        CandidateVideo candidate = mockCandidate(vid, authorId, "entertainment", "gossip");
        when(candidateVideoRepository.findByVid(vid)).thenReturn(Optional.of(candidate));

        UserProfile profile = UserProfile.initialize(userId, 512);
        when(userProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));

        feedbackApplicationService.recordFeedback(
                userId, vid, FeedbackActionType.DISLIKE, 2, 20, "DISLIKE_AUTHOR", "trace_dislike", LocalDateTime.now()
        );

        // 验证自动添加屏蔽黑名单
        verify(userBlockRepository).save(argThat(block ->
                block.getUserId().equals(userId)
                        && block.getBlockType() == BlockType.AUTHOR
                        && block.getTargetId().equals(authorId)
        ));

        verify(userProfileRepository).saveOrUpdate(any());
    }

    @Test
    @DisplayName("游客未登录行为：只如实沉淀流水日志，不驱动任何画像演进")
    void shouldOnlySaveLogForGuest() {
        String vid = "vid_guest_01";
        CandidateVideo candidate = mockCandidate(vid, "author_z", "tech", "cloud");
        when(candidateVideoRepository.findByVid(vid)).thenReturn(Optional.of(candidate));

        feedbackApplicationService.recordFeedback(
                null, vid, FeedbackActionType.PLAY, 30, 30, null, "trace_guest", LocalDateTime.now()
        );

        verify(feedbackLogRepository).save(argThat(log -> "anonymous".equals(log.getUserId())));
        verify(userProfileRepository, never()).findByUserId(anyString());
        verify(userProfileRepository, never()).saveOrUpdate(any());
    }
}
