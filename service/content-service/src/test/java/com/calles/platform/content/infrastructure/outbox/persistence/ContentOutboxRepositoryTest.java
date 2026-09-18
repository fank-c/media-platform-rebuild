package com.calles.platform.content.infrastructure.outbox.persistence;

import com.calles.platform.content.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.content.infrastructure.outbox.model.ContentOutboxStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContentOutboxRepository 仓储逻辑单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentOutboxRepository 仓储逻辑单元测试")
class ContentOutboxRepositoryTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-17T12:00:00Z");

    @Mock
    private ContentOutboxMapper outboxMapper;

    private ContentOutboxRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ContentOutboxRepository(outboxMapper, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("CAS 条件认领竞争失败时返回 null 且不读取快照")
    void claimByEventIdReturnsNullWhenCompetitionLost() {
        when(outboxMapper.markClaimedIfEligible(eq("event-1"), eq("PROCESSING"), eq("node-1"), any(), any(),
                eq(20), eq("PENDING"), any(), eq("PROCESSING"), any())).thenReturn(0);

        ClaimedOutboxMessage result = repository.claimByEventId("event-1", "node-1", Duration.ofSeconds(30), 20);

        assertNull(result);
    }

    @Test
    @DisplayName("CAS 条件认领成功后按同一 token 返回快照")
    void claimByEventIdReturnsSnapshotWhenCompetitionWon() {
        ClaimedOutboxMessage expected = new ClaimedOutboxMessage(
                "event-1", "content.video.submitted", "{}", "trace-1", "token-1", 1
        );
        when(outboxMapper.markClaimedIfEligible(eq("event-1"), eq("PROCESSING"), eq("node-1"), any(), any(),
                eq(20), eq("PENDING"), any(), eq("PROCESSING"), any())).thenReturn(1);
        when(outboxMapper.findClaimedMessage(eq("event-1"), eq("PROCESSING"), any())).thenReturn(expected);

        ClaimedOutboxMessage result = repository.claimByEventId("event-1", "node-1", Duration.ofSeconds(30), 20);

        assertEquals(expected, result);
        verify(outboxMapper).findClaimedMessage(eq("event-1"), eq("PROCESSING"), any());
    }

    @Test
    @DisplayName("尝试将超限过期记录置位为 FAILED")
    void markExhaustedIfEligibleUpdatesCorrectly() {
        when(outboxMapper.markExhaustedIfEligible(eq("event-1"), eq("FAILED"), eq("ATTEMPTS_EXHAUSTED"),
                eq(20), eq("PENDING"), any(), eq("PROCESSING"), any())).thenReturn(1, 0);

        assertTrue(repository.markExhaustedIfEligible("event-1", 20));
        assertFalse(repository.markExhaustedIfEligible("event-1", 20));
    }

    @Test
    @DisplayName("发布成功更新状态为 PUBLISHED 并返回生效行数")
    void markPublishedReturnsSuccess() {
        ClaimedOutboxMessage msg = new ClaimedOutboxMessage("event-1", "type", "{}", "trace", "token-1", 1);
        when(outboxMapper.markPublished(eq("event-1"), eq("PUBLISHED"), any(), eq("PROCESSING"), eq("token-1")))
                .thenReturn(1);

        assertTrue(repository.markPublished(msg));
    }

    @Test
    @DisplayName("失败回写：未达上限时保持 PENDING，达到上限时转为 FAILED")
    void markFailedHandlesRetryAndTerminalCorrectly() {
        when(outboxMapper.markFailed(any(), any(), any(), any(), any(), any())).thenReturn(1);

        ClaimedOutboxMessage retryMsg = new ClaimedOutboxMessage("event-retry", "type", "{}", "trace", "token-1", 1);
        ClaimedOutboxMessage terminalMsg = new ClaimedOutboxMessage("event-terminal", "type", "{}", "trace", "token-2", 2);

        assertTrue(repository.markFailed(retryMsg, 2, "Timeout"));
        assertTrue(repository.markFailed(terminalMsg, 2, "Timeout"));

        verify(outboxMapper).markFailed(eq("event-retry"), eq(ContentOutboxStatus.PENDING.databaseValue()), any(),
                eq("Timeout"), eq(ContentOutboxStatus.PROCESSING.databaseValue()), eq("token-1"));
        verify(outboxMapper).markFailed(eq("event-terminal"), eq(ContentOutboxStatus.FAILED.databaseValue()), any(),
                eq("Timeout"), eq(ContentOutboxStatus.PROCESSING.databaseValue()), eq("token-2"));
    }
}
