package com.calles.platform.user.infrastructure.outbox.dispatch;

import com.calles.platform.user.config.UserOutboxProperties;
import com.calles.platform.user.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.user.infrastructure.outbox.persistence.UserOutboxRepository;
import java.time.Instant;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("UserOutboxDispatcher 任务调度器测试")
class UserOutboxDispatcherTest {

    @Mock
    private UserOutboxRepository repository;

    @Mock
    private UserOutboxPublisher publisher;

    private UserOutboxProperties properties;
    private UserOutboxDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        properties = new UserOutboxProperties();
        dispatcher = new UserOutboxDispatcher(repository, publisher, properties);
    }

    @Test
    @DisplayName("总开关关闭时不执行认领与投递")
    void testDisabled() {
        properties.setEnabled(false);
        dispatcher.dispatchByEventId("evt-1");
        verify(repository, never()).claimByEventId(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("认领成功后交由 publisher 投递")
    void testDispatchByEventIdSuccess() {
        ClaimedOutboxMessage claimed = new ClaimedOutboxMessage(
                "evt-1", "u1", "interaction.author-action", 1, "{}", "tr", Instant.now(), 1, "tok"
        );
        when(repository.claimByEventId(eq("evt-1"), anyString(), any(), eq(properties.getMaxAttempts())))
                .thenReturn(claimed);

        dispatcher.dispatchByEventId("evt-1");

        verify(publisher).publish(claimed);
    }

    @Test
    @DisplayName("定时扫描批量拉取并处理")
    void testDispatchScanBatch() {
        when(repository.findClaimableEventIds(properties.getBatchSize(), properties.getMaxAttempts()))
                .thenReturn(List.of("evt-1", "evt-2"));

        dispatcher.dispatchScanBatch();

        verify(repository).claimByEventId(eq("evt-1"), anyString(), any(), eq(properties.getMaxAttempts()));
        verify(repository).claimByEventId(eq("evt-2"), anyString(), any(), eq(properties.getMaxAttempts()));
    }
}
