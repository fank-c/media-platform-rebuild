package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.interfaces.messaging.event.VideoPublishedMessage;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CandidateVideoApplicationService 推荐候选池应用服务单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CandidateVideoApplicationService 应用服务测试")
class CandidateVideoApplicationServiceTest {

    @Mock
    private CandidateVideoRepository candidateVideoRepository;

    @InjectMocks
    private CandidateVideoApplicationService applicationService;

    @Test
    @DisplayName("handlePublished：全新视频首次发布入池成功")
    void shouldHandleNewVideoPublished() {
        VideoPublishedMessage message = new VideoPublishedMessage(
                "evt_1", "content.video.published", "trace_1",
                "vid_100", "cv_abc", "author_1",
                "tag_dom_01", "tag_top_01", LocalDateTime.now()
        );
        when(candidateVideoRepository.findByVideoId("vid_100")).thenReturn(Optional.empty());
        when(candidateVideoRepository.insert(any())).thenReturn(1);

        applicationService.handlePublished(message);

        verify(candidateVideoRepository).insert(any());
    }

    @Test
    @DisplayName("handlePublished：已存在且处于 ACTIVE 态时幂等快速跳过")
    void shouldSkipWhenAlreadyActive() {
        CandidateVideo existing = CandidateVideo.createPublished(
                "c_01", "vid_100", "cv_abc", "author_1", "dom", "top", LocalDateTime.now()
        );
        when(candidateVideoRepository.findByVideoId("vid_100")).thenReturn(Optional.of(existing));

        VideoPublishedMessage message = new VideoPublishedMessage(
                "evt_1", "content.video.published", "trace_1",
                "vid_100", "cv_abc", "author_1", "dom", "top", LocalDateTime.now()
        );

        applicationService.handlePublished(message);

        verify(candidateVideoRepository, never()).insert(any());
        verify(candidateVideoRepository, never()).updateStatusByVideoId(any(), any());
    }

    @Test
    @DisplayName("handlePublished：已下线视频重新上架重新激活为 ACTIVE")
    void shouldReactivateWhenPreviouslyOffline() {
        CandidateVideo existing = CandidateVideo.createPublished(
                "c_01", "vid_100", "cv_abc", "author_1", "dom", "top", LocalDateTime.now()
        );
        existing.markOffline();
        when(candidateVideoRepository.findByVideoId("vid_100")).thenReturn(Optional.of(existing));

        VideoPublishedMessage message = new VideoPublishedMessage(
                "evt_1", "content.video.published", "trace_1",
                "vid_100", "cv_abc", "author_1", "dom", "top", LocalDateTime.now()
        );

        applicationService.handlePublished(message);

        verify(candidateVideoRepository).updateStatusByVideoId("vid_100", CandidateStatus.ACTIVE);
        verify(candidateVideoRepository, never()).insert(any());
    }

    @Test
    @DisplayName("handlePublished：并发写入遇到唯一键冲突安全幂等放行")
    void shouldHandleDuplicateKeyExceptionGracefully() {
        VideoPublishedMessage message = new VideoPublishedMessage(
                "evt_1", "content.video.published", "trace_1",
                "vid_100", "cv_abc", "author_1", "dom", "top", LocalDateTime.now()
        );
        when(candidateVideoRepository.findByVideoId("vid_100")).thenReturn(Optional.empty());
        when(candidateVideoRepository.insert(any())).thenThrow(new DuplicateKeyException("uk_rcv_video_id conflict"));

        applicationService.handlePublished(message);

        verify(candidateVideoRepository).insert(any());
    }

    @Test
    @DisplayName("handleOfflined：创作者主动下线，状态变更为 OFFLINE")
    void shouldHandleOfflined() {
        applicationService.handleOfflined("vid_100", "cv_abc", "创作者下架");

        verify(candidateVideoRepository).updateStatusByVideoId("vid_100", CandidateStatus.OFFLINE);
    }

    @Test
    @DisplayName("handleBanned：违规封禁熔断，状态变更为 BANNED")
    void shouldHandleBanned() {
        applicationService.handleBanned("vid_100", "cv_abc", "违规涉政");

        verify(candidateVideoRepository).updateStatusByVideoId("vid_100", CandidateStatus.BANNED);
    }
}
