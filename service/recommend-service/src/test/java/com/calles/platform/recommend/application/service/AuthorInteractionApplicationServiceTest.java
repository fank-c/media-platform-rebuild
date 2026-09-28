package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.domain.model.event.EventConsumedRecord;
import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.domain.model.feedback.FeedbackLog;
import com.calles.platform.recommend.domain.repository.EventConsumedRecordRepository;
import com.calles.platform.recommend.domain.repository.FeedbackLogRepository;
import com.calles.platform.recommend.interfaces.messaging.event.InteractionAuthorActionMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AuthorInteractionApplicationService 单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorInteractionApplicationService 作者互动应用服务测试")
class AuthorInteractionApplicationServiceTest {

    @Mock
    private EventConsumedRecordRepository eventConsumedRecordRepository;

    @Mock
    private FeedbackLogRepository feedbackLogRepository;

    private AuthorInteractionApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AuthorInteractionApplicationService(eventConsumedRecordRepository, feedbackLogRepository);
    }

    @Test
    @DisplayName("处理关注生效事件 (FOLLOW + ACTIVE)：记录幂等并落库 FOLLOW 行为流水")
    void shouldHandleFollowActiveEventSuccessfully() {
        InteractionAuthorActionMessage message = new InteractionAuthorActionMessage(
                "evt_follow_001",
                "interaction.author-action",
                1,
                "tr_follow_001",
                "2026-09-28T09:00:00Z",
                new InteractionAuthorActionMessage.Payload("user_01", "author_01", "FOLLOW", "ACTIVE")
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any(EventConsumedRecord.class))).thenReturn(true);

        service.handleAuthorAction(message);

        // 验证幂等检查入参
        ArgumentCaptor<EventConsumedRecord> recordCaptor = ArgumentCaptor.forClass(EventConsumedRecord.class);
        verify(eventConsumedRecordRepository).saveIfAbsent(recordCaptor.capture());
        EventConsumedRecord record = recordCaptor.getValue();
        assertThat(record.getEventId()).isEqualTo("evt_follow_001");
        assertThat(record.getEventType()).isEqualTo("interaction.author-action");
        assertThat(record.getUserId()).isEqualTo("user_01");
        assertThat(record.getAuthorId()).isEqualTo("author_01");
        assertThat(record.getVid()).isNull();
        assertThat(record.getAction()).isEqualTo("FOLLOW");
        assertThat(record.getState()).isEqualTo("ACTIVE");

        // 验证行为事实流水
        ArgumentCaptor<FeedbackLog> logCaptor = ArgumentCaptor.forClass(FeedbackLog.class);
        verify(feedbackLogRepository).save(logCaptor.capture());
        FeedbackLog feedbackLog = logCaptor.getValue();
        assertThat(feedbackLog.getUserId()).isEqualTo("user_01");
        assertThat(feedbackLog.getAuthorId()).isEqualTo("author_01");
        assertThat(feedbackLog.getVid()).isNull();
        assertThat(feedbackLog.getActionType()).isEqualTo(FeedbackActionType.FOLLOW);
        assertThat(feedbackLog.getTraceId()).isEqualTo("tr_follow_001");
    }

    @Test
    @DisplayName("处理取消关注事件 (FOLLOW + INACTIVE)：记录幂等并落库 UNFOLLOW 行为流水")
    void shouldHandleFollowInactiveEventSuccessfully() {
        InteractionAuthorActionMessage message = new InteractionAuthorActionMessage(
                "evt_unfollow_002",
                "interaction.author-action",
                1,
                "tr_unfollow_002",
                "2026-09-28T09:05:00Z",
                new InteractionAuthorActionMessage.Payload("user_01", "author_01", "FOLLOW", "INACTIVE")
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any(EventConsumedRecord.class))).thenReturn(true);

        service.handleAuthorAction(message);

        ArgumentCaptor<FeedbackLog> logCaptor = ArgumentCaptor.forClass(FeedbackLog.class);
        verify(feedbackLogRepository).save(logCaptor.capture());
        FeedbackLog feedbackLog = logCaptor.getValue();
        assertThat(feedbackLog.getUserId()).isEqualTo("user_01");
        assertThat(feedbackLog.getAuthorId()).isEqualTo("author_01");
        assertThat(feedbackLog.getVid()).isNull();
        assertThat(feedbackLog.getActionType()).isEqualTo(FeedbackActionType.UNFOLLOW);
    }

    @Test
    @DisplayName("幂等防御：重复事件已被消费过时，安全忽略并不重复写入行为流水")
    void shouldIgnoreWhenEventAlreadyConsumed() {
        InteractionAuthorActionMessage message = new InteractionAuthorActionMessage(
                "evt_dup_003",
                "interaction.author-action",
                1,
                "tr_dup_003",
                "2026-09-28T09:10:00Z",
                new InteractionAuthorActionMessage.Payload("user_01", "author_01", "FOLLOW", "ACTIVE")
        );
        when(eventConsumedRecordRepository.saveIfAbsent(any(EventConsumedRecord.class))).thenReturn(false);

        service.handleAuthorAction(message);

        verify(eventConsumedRecordRepository).saveIfAbsent(any(EventConsumedRecord.class));
        verifyNoInteractions(feedbackLogRepository);
    }

    @Test
    @DisplayName("非法载荷校验：缺少 userId 或 authorId 或非法 action/state 时防御性丢弃")
    void shouldDropMessageWhenPayloadInvalid() {
        // 缺少 authorId
        InteractionAuthorActionMessage missingAuthor = new InteractionAuthorActionMessage(
                "evt_invalid_004", "interaction.author-action", 1, "tr_004", "2026-09-28T09:15:00Z",
                new InteractionAuthorActionMessage.Payload("user_01", null, "FOLLOW", "ACTIVE")
        );
        service.handleAuthorAction(missingAuthor);

        // 非法 action
        InteractionAuthorActionMessage invalidAction = new InteractionAuthorActionMessage(
                "evt_invalid_005", "interaction.author-action", 1, "tr_005", "2026-09-28T09:15:00Z",
                new InteractionAuthorActionMessage.Payload("user_01", "author_01", "BLOCK", "ACTIVE")
        );
        service.handleAuthorAction(invalidAction);

        // 非法 state
        InteractionAuthorActionMessage invalidState = new InteractionAuthorActionMessage(
                "evt_invalid_006", "interaction.author-action", 1, "tr_006", "2026-09-28T09:15:00Z",
                new InteractionAuthorActionMessage.Payload("user_01", "author_01", "FOLLOW", "UNKNOWN_STATE")
        );
        service.handleAuthorAction(invalidState);

        verifyNoInteractions(eventConsumedRecordRepository);
        verifyNoInteractions(feedbackLogRepository);
    }
}
