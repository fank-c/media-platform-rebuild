package com.calles.platform.content.infrastructure.outbox.dispatch;

import com.calles.platform.content.config.ContentOutboxProperties;
import com.calles.platform.content.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.content.infrastructure.outbox.persistence.ContentOutboxRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContentOutboxDispatcher 任务分发器单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentOutboxDispatcher 统一分发器测试")
class ContentOutboxDispatcherTest {

    @Mock
    private ContentOutboxRepository repository;

    @Mock
    private ContentOutboxPublisher publisher;

    private ContentOutboxProperties properties;
    private ContentOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new ContentOutboxProperties();
        dispatcher = new ContentOutboxDispatcher(repository, publisher, properties);
    }

    @Test
    @DisplayName("总开关关闭时跳过分发")
    void disabledDispatcherSkips() {
        properties.setEnabled(false);

        dispatcher.dispatchByEventId("event-1");

        verify(repository, never()).claimByEventId(anyString(), anyString(), any(), anyInt());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("认领成功时触发网络投递")
    void claimedMessageTriggersPublish() {
        ClaimedOutboxMessage claimed = new ClaimedOutboxMessage(
                "event-1", "type", "{}", "trace", "token-1", 1
        );
        when(repository.claimByEventId(eq("event-1"), anyString(), any(), eq(20))).thenReturn(claimed);

        dispatcher.dispatchByEventId("event-1");

        verify(publisher).publish(claimed);
    }

    @Test
    @DisplayName("认领失败时尝试收敛超限状态")
    void unclaimedMessageAttemptsExhaustionConvergence() {
        when(repository.claimByEventId(eq("event-1"), anyString(), any(), eq(20))).thenReturn(null);

        dispatcher.dispatchByEventId("event-1");

        verify(repository).markExhaustedIfEligible("event-1", 20);
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("扫描批次拉取候选并逐条派发")
    void scanBatchDispatchesAllCandidates() {
        when(repository.findClaimableEventIds(100, 20)).thenReturn(List.of("event-1", "event-2"));
        when(repository.findExhaustedEventIds(100, 20)).thenReturn(List.of("event-old"));

        dispatcher.dispatchScanBatch();

        verify(repository).claimByEventId(eq("event-1"), anyString(), any(), eq(20));
        verify(repository).claimByEventId(eq("event-2"), anyString(), any(), eq(20));
        verify(repository).markExhaustedIfEligible(eq("event-old"), eq(20));
    }
}
