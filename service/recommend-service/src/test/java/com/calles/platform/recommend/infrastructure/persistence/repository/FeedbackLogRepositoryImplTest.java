package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.domain.model.feedback.FeedbackLog;
import com.calles.platform.recommend.infrastructure.persistence.entity.FeedbackLogPO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.FeedbackLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FeedbackLogRepositoryImpl 仓储实现单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackLogRepositoryImpl 仓储实现测试")
class FeedbackLogRepositoryImplTest {

    @Mock
    private FeedbackLogMapper feedbackLogMapper;

    @InjectMocks
    private FeedbackLogRepositoryImpl repository;

    @Test
    @DisplayName("save：单笔保存行为流水")
    void shouldSaveFeedbackLog() {
        FeedbackLog log = FeedbackLog.record(
                "u_100", "vid_999", FeedbackActionType.PLAY, 30, 60, "d1", "t1,t2", "a1", "trace-1", LocalDateTime.now()
        );
        when(feedbackLogMapper.insert(any(FeedbackLogPO.class))).thenReturn(1);

        repository.save(log);

        verify(feedbackLogMapper).insert(any(FeedbackLogPO.class));
    }

    @Test
    @DisplayName("saveBatch：批量保存行为流水")
    void shouldSaveBatchFeedbackLogs() {
        FeedbackLog log1 = FeedbackLog.record("u_100", "v1", FeedbackActionType.IMPRESSION, 0, 60, null, null, null, null, null);
        FeedbackLog log2 = FeedbackLog.record("u_100", "v2", FeedbackActionType.SKIP, 2, 60, null, null, null, null, null);

        when(feedbackLogMapper.insert(any(FeedbackLogPO.class))).thenReturn(1);

        repository.saveBatch(List.of(log1, log2));

        verify(feedbackLogMapper, times(2)).insert(any(FeedbackLogPO.class));
    }

    @Test
    @DisplayName("findRecentByUserId：分页倒序查询用户近期行为")
    void shouldFindRecentByUserId() {
        FeedbackLogPO po = new FeedbackLogPO();
        po.setId("f_001");
        po.setUserId("u_100");
        po.setVid("v1");
        po.setActionType("PLAY");
        po.setPlayDuration(15);
        po.setVideoDuration(30);
        po.setOccurredAt(LocalDateTime.now());
        po.setCreatedAt(LocalDateTime.now());

        when(feedbackLogMapper.selectList(any())).thenReturn(List.of(po));

        List<FeedbackLog> logs = repository.findRecentByUserId("u_100", 10);

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActionType()).isEqualTo(FeedbackActionType.PLAY);
        assertThat(logs.get(0).calculatePlayRatio()).isEqualTo(0.5);
    }
}
