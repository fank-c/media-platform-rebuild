package com.calles.platform.interaction.infrastructure.outbox.dispatch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InteractionOutboxDispatcherTest {

    @Mock
    private InteractionOutboxRepository repository;

    @Mock
    private InteractionOutboxPublisher publisher;

    private InteractionOutboxProperties properties;
    private InteractionOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new InteractionOutboxProperties();
        properties.setEnabled(true);
        properties.setDispatchEnabled(false); // 默认关闭派发
        dispatcher = new InteractionOutboxDispatcher(repository, publisher, properties);
    }

    @Test
    @DisplayName("派发开关关闭时跳过单条与批量分发")
    void shouldSkipDispatchWhenDispatchDisabled() {
        dispatcher.dispatchByEventId("evt_01");
        dispatcher.dispatchScanBatch();

        verify(repository, never()).claimByEventId(anyString(), anyString(), any(), anyInt());
        verify(repository, never()).findClaimableEventIds(anyInt(), anyInt());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("派发开关开启时成功认领并交由Publisher发送")
    void shouldClaimAndPublishWhenDispatchEnabled() {
        properties.setDispatchEnabled(true);
        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt_01", "vid_100", "interaction.video-action", 1,
                "{}", "trace_01", Instant.now(), 1, "token_123"
        );
        when(repository.claimByEventId(eq("evt_01"), anyString(), any(), eq(properties.getMaxAttempts())))
                .thenReturn(message);

        dispatcher.dispatchByEventId("evt_01");

        verify(publisher).publish(message);
    }

    @Test
    @DisplayName("扫描批次时遍历候选ID并处理耗尽记录")
    void shouldProcessScanBatch() {
        properties.setDispatchEnabled(true);
        when(repository.findClaimableEventIds(eq(properties.getBatchSize()), eq(properties.getMaxAttempts())))
                .thenReturn(List.of("evt_01"));
        when(repository.findExhaustedEventIds(eq(properties.getBatchSize()), eq(properties.getMaxAttempts())))
                .thenReturn(List.of("evt_99"));

        dispatcher.dispatchScanBatch();

        verify(repository).findClaimableEventIds(eq(properties.getBatchSize()), eq(properties.getMaxAttempts()));
        verify(repository).markExhaustedIfEligible("evt_99", properties.getMaxAttempts());
    }
}
