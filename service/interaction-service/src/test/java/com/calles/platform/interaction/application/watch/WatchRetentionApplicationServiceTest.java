package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.config.InteractionWatchProperties;
import com.calles.platform.interaction.domain.repository.WatchEventClaimRepository;
import com.calles.platform.interaction.domain.repository.WatchProgressRepository;
import com.calles.platform.interaction.domain.repository.WatchSessionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 观看数据保留期清理应用服务单元测试。
 *
 * <p>验证清理顺序（先解除活跃引用再删除会话）与保留期边界，确保清理不会重新开启播放量计数。</p>
 */
@ExtendWith(MockitoExtension.class)
class WatchRetentionApplicationServiceTest {

    @Mock
    private WatchProgressRepository progressRepository;

    @Mock
    private WatchSessionRepository sessionRepository;

    @Mock
    private WatchEventClaimRepository claimRepository;

    private InteractionWatchProperties properties;
    private WatchRetentionApplicationService service;

    @BeforeEach
    void setUp() {
        properties = new InteractionWatchProperties();
        properties.setRetention(Duration.ofDays(30));
        properties.setCleanupBatchSize(200);
        service = new WatchRetentionApplicationService(
                progressRepository, sessionRepository, claimRepository, properties);
    }

    @Test
    @DisplayName("清理按保留期阈值执行，并覆盖会话引用、会话、凭据与隐藏进度四类数据")
    void shouldCleanupAllWatchDataKinds() {
        when(progressRepository.detachStaleActiveSessions(any(), anyInt())).thenReturn(3);
        when(sessionRepository.deleteStaleBefore(any(), anyInt())).thenReturn(2);
        when(claimRepository.deleteBefore(any(), anyInt())).thenReturn(5);
        when(progressRepository.deleteHiddenBefore(any(), anyInt())).thenReturn(1);

        service.cleanupExpired();

        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(progressRepository).detachStaleActiveSessions(thresholdCaptor.capture(), eq(200));
        verify(sessionRepository).deleteStaleBefore(thresholdCaptor.capture(), eq(200));
        verify(claimRepository).deleteBefore(thresholdCaptor.capture(), eq(200));
        verify(progressRepository).deleteHiddenBefore(thresholdCaptor.capture(), eq(200));

        LocalDateTime expected = LocalDateTime.now().minusDays(30);
        for (LocalDateTime actual : thresholdCaptor.getAllValues()) {
            assertThat(Duration.between(expected, actual).abs()).isLessThan(Duration.ofMinutes(1));
        }
    }

    @Test
    @DisplayName("清理批大小非法时收敛为至少一行，避免配置错误导致任务空转")
    void shouldNormalizeInvalidBatchSize() {
        properties.setCleanupBatchSize(0);

        service.cleanupExpired();

        verify(progressRepository).detachStaleActiveSessions(any(), eq(1));
        verify(sessionRepository).deleteStaleBefore(any(), eq(1));
        verify(claimRepository).deleteBefore(any(), eq(1));
        verify(progressRepository).deleteHiddenBefore(any(), eq(1));
    }

    @Test
    @DisplayName("清理顺序必须为解除活跃引用先于删除会话")
    void shouldDetachActiveSessionsBeforeDeletingSessions() {
        when(progressRepository.detachStaleActiveSessions(any(), anyInt())).thenReturn(0);
        when(sessionRepository.deleteStaleBefore(any(), anyInt())).thenReturn(0);
        when(claimRepository.deleteBefore(any(), anyInt())).thenReturn(0);
        when(progressRepository.deleteHiddenBefore(any(), anyInt())).thenReturn(0);

        service.cleanupExpired();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(progressRepository, sessionRepository);
        order.verify(progressRepository).detachStaleActiveSessions(any(), anyInt());
        order.verify(sessionRepository).deleteStaleBefore(any(), anyInt());
    }

    @Test
    @DisplayName("保留期未配置时回退默认 30 天，不删除近期数据")
    void shouldFallbackToDefaultRetentionWhenAbsent() {
        properties.setRetention(null);

        service.cleanupExpired();

        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(progressRepository).detachStaleActiveSessions(thresholdCaptor.capture(), eq(200));
        LocalDateTime expected = LocalDateTime.now().minusDays(30);
        assertThat(Duration.between(expected, thresholdCaptor.getValue()).abs())
                .isLessThan(Duration.ofMinutes(1));
    }
}
