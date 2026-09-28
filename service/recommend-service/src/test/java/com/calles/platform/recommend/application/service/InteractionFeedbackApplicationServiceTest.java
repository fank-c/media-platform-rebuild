package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.config.RecommendEmbeddingProperties;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.VideoVector;
import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.EventConsumedRecordRepository;
import com.calles.platform.recommend.domain.repository.FeedbackLogRepository;
import com.calles.platform.recommend.domain.repository.UserProfileRepository;
import com.calles.platform.recommend.domain.repository.VideoVectorRepository;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionVideoActionMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * InteractionFeedbackApplicationService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InteractionFeedbackApplicationService 互动事件反馈与画像测试")
class InteractionFeedbackApplicationServiceTest {

    @Mock
    private EventConsumedRecordRepository eventConsumedRecordRepository;
    @Mock
    private FeedbackLogRepository feedbackLogRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private CandidateVideoRepository candidateVideoRepository;
    @Mock
    private VideoVectorRepository videoVectorRepository;
    @Spy
    private RecommendEmbeddingProperties embeddingProperties = new RecommendEmbeddingProperties();
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private InteractionFeedbackApplicationService service;

    private CandidateVideo mockCandidate(String vid) {
        return CandidateVideo.createPublished("c1", "vid_001", vid, "author_1", "domain_tech", "topic_ai", LocalDateTime.now());
    }

    @Test
    @DisplayName("幂等拦截：已消费过的事件直接跳过，不产生任何流水或画像更新")
    void shouldSkipWhenEventAlreadyConsumed() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_duplicate", "interaction.video-action", 1, "trace_01", "2026-09-28T03:00:00Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "LIKE", "ACTIVE", null, null, null)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(false);

        service.handleInteractionEvent(message);

        verify(eventConsumedRecordRepository).saveIfAbsent(any());
        verifyNoInteractions(candidateVideoRepository, feedbackLogRepository, userProfileRepository);
    }

    @Test
    @DisplayName("点赞生效：首次消费成功记录 LIKE 流水并驱动画像正向演进")
    void shouldProcessLikeActiveAndDriveProfile() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_like_01", "interaction.video-action", 1, "trace_01", "2026-09-28T03:00:00Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "LIKE", "ACTIVE", null, null, null)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(true);
        when(candidateVideoRepository.findByVid("v1")).thenReturn(Optional.of(mockCandidate("v1")));
        when(userProfileRepository.findByUserId("u1")).thenReturn(Optional.of(UserProfile.initialize("u1")));

        service.handleInteractionEvent(message);

        verify(feedbackLogRepository).save(argThat(log ->
                log.getUserId().equals("u1") && log.getVid().equals("v1") && log.getActionType() == FeedbackActionType.LIKE
        ));
        verify(userProfileRepository).saveOrUpdate(argThat(profile ->
                profile.getRecentWatchItems().stream().anyMatch(item -> item.getVid().equals("v1"))
        ));
    }

    @Test
    @DisplayName("取消点赞：仅记录 UNLIKE 事实流水，不主动调用画像更新")
    void shouldProcessUnlikeInactiveAndOnlyRecordLog() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_unlike_01", "interaction.video-action", 1, "trace_01", "2026-09-28T03:00:00Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "LIKE", "INACTIVE", null, null, null)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(true);
        when(candidateVideoRepository.findByVid("v1")).thenReturn(Optional.of(mockCandidate("v1")));

        service.handleInteractionEvent(message);

        verify(feedbackLogRepository).save(argThat(log ->
                log.getActionType() == FeedbackActionType.UNLIKE
        ));
        verifyNoInteractions(userProfileRepository);
    }

    @Test
    @DisplayName("收藏生效：首次消费记录 STAR 流水并推动画像演进")
    void shouldProcessStarActive() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_star_01", "interaction.video-action", 1, "trace_01", "2026-09-28T03:00:00Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "STAR", "ACTIVE", null, null, null)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(true);
        when(candidateVideoRepository.findByVid("v1")).thenReturn(Optional.of(mockCandidate("v1")));
        when(userProfileRepository.findByUserId("u1")).thenReturn(Optional.of(UserProfile.initialize("u1")));

        service.handleInteractionEvent(message);

        verify(feedbackLogRepository).save(argThat(log ->
                log.getActionType() == FeedbackActionType.STAR
        ));
        verify(userProfileRepository).saveOrUpdate(any());
    }

    @Test
    @DisplayName("取消收藏：仅记录 UNSTAR 流水，不推动画像更新")
    void shouldProcessUnstarInactive() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_unstar_01", "interaction.video-action", 1, "trace_01", "2026-09-28T03:00:00Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "STAR", "INACTIVE", null, null, null)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(true);
        when(candidateVideoRepository.findByVid("v1")).thenReturn(Optional.of(mockCandidate("v1")));

        service.handleInteractionEvent(message);

        verify(feedbackLogRepository).save(argThat(log ->
                log.getActionType() == FeedbackActionType.UNSTAR
        ));
        verifyNoInteractions(userProfileRepository);
    }

    @Test
    @DisplayName("分享事件：记录 SHARE 流水并以最高权重演进画像")
    void shouldProcessShareActive() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_share_01", "interaction.video-action", 1, "trace_01", "2026-09-28T03:00:00Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "SHARE", "ACTIVE", null, null, null)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(true);
        when(candidateVideoRepository.findByVid("v1")).thenReturn(Optional.of(mockCandidate("v1")));
        when(userProfileRepository.findByUserId("u1")).thenReturn(Optional.of(UserProfile.initialize("u1")));

        service.handleInteractionEvent(message);

        verify(feedbackLogRepository).save(argThat(log ->
                log.getActionType() == FeedbackActionType.SHARE
        ));
        verify(userProfileRepository).saveOrUpdate(any());
    }

    @Test
    @DisplayName("有效观看资格事件：时长合法时记录 WATCH_VIEW_QUALIFIED 流水并更新画像")
    void shouldProcessWatchViewQualified() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_wvq_01", "interaction.video-action", 1, "trace_01", "2026-09-28T03:00:00Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "WATCH_VIEW_QUALIFIED", null, "sess_1", 20, 60)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(true);
        when(candidateVideoRepository.findByVid("v1")).thenReturn(Optional.of(mockCandidate("v1")));
        when(userProfileRepository.findByUserId("u1")).thenReturn(Optional.of(UserProfile.initialize("u1")));

        service.handleInteractionEvent(message);

        verify(feedbackLogRepository).save(argThat(log ->
                log.getActionType() == FeedbackActionType.WATCH_VIEW_QUALIFIED
                        && log.getPlayDuration() == 20
                        && log.getVideoDuration() == 60
        ));
        verify(userProfileRepository).saveOrUpdate(any());
    }

    @Test
    @DisplayName("有效观看资格事件时长非法时：丢弃且不记录流水")
    void shouldDropWatchViewQualifiedWhenDurationInvalid() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_wvq_invalid", "interaction.video-action", 1, "trace_01", "2026-09-28T03:00:00Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "WATCH_VIEW_QUALIFIED", null, "sess_1", 0, 60)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(true);
        when(candidateVideoRepository.findByVid("v1")).thenReturn(Optional.of(mockCandidate("v1")));

        service.handleInteractionEvent(message);

        verifyNoInteractions(feedbackLogRepository, userProfileRepository);
    }

    @Test
    @DisplayName("完播事件：记录 WATCH_COMPLETED 流水并更新画像")
    void shouldProcessWatchCompleted() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_wc_01", "interaction.video-action", 1, "trace_01", "2026-09-28T03:00:00Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "WATCH_COMPLETED", null, "sess_1", 58, 60)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(true);
        when(candidateVideoRepository.findByVid("v1")).thenReturn(Optional.of(mockCandidate("v1")));
        when(userProfileRepository.findByUserId("u1")).thenReturn(Optional.of(UserProfile.initialize("u1")));

        service.handleInteractionEvent(message);

        verify(feedbackLogRepository).save(argThat(log ->
                log.getActionType() == FeedbackActionType.WATCH_COMPLETED
                        && log.getPlayDuration() == 58
                        && log.getVideoDuration() == 60
        ));
        verify(userProfileRepository).saveOrUpdate(any());
    }

    @Test
    @DisplayName("时间戳解析容错：支持带 'Z' 后缀的 ISO-8601 UTC 字符串")
    void shouldParseIsoInstantOccurredAtCorrectly() {
        InteractionVideoActionMessage message = new InteractionVideoActionMessage(
                "evt_time_01", "interaction.video-action", 1, "trace_01", "2026-09-28T03:33:41.123Z",
                new InteractionVideoActionMessage.Payload("u1", "v1", "SHARE", "ACTIVE", null, null, null)
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any())).thenReturn(true);
        when(candidateVideoRepository.findByVid("v1")).thenReturn(Optional.of(mockCandidate("v1")));
        when(userProfileRepository.findByUserId("u1")).thenReturn(Optional.of(UserProfile.initialize("u1")));

        service.handleInteractionEvent(message);

        verify(feedbackLogRepository).save(argThat(log -> log.getOccurredAt() != null));
    }
}
