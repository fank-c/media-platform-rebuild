package com.calles.platform.auth.infrastructure.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** AuthOutboxRepository 对条件领取、终态收敛和 token 回写的单元测试。 */
@ExtendWith(MockitoExtension.class)
class AuthOutboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-06T08:00:00Z");

    @Mock private AuthOutboxMapper outboxMapper;
    private AuthOutboxRepository repository;

    /** 使用固定 UTC 时钟，使资格边界和租约参数可精确断言。 */
    @BeforeEach
    void setUp() {
        repository = new AuthOutboxRepository(outboxMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 条件更新返回 0 是正常竞争失败，不读取快照、不增加发送工作。 */
    @Test
    void claimByEventIdSkipsWhenConditionalUpdateLosesCompetition() {
        when(outboxMapper.markClaimedIfEligible(eq("event-1"), eq("PROCESSING"), eq("owner"), any(), any(),
                eq(20), eq("PENDING"), any(), eq("PROCESSING"), any())).thenReturn(0);

        ClaimedOutboxMessage result = repository.claimByEventId("event-1", "owner", Duration.ofSeconds(30), 20);

        assertNull(result);
    }

    /** 只有条件更新成功后，才读取同一 token 的发送快照。 */
    @Test
    void claimByEventIdReturnsSnapshotAfterConditionalUpdate() {
        ClaimedOutboxMessage expected = new ClaimedOutboxMessage("event-1", "{}", "token", 1);
        when(outboxMapper.markClaimedIfEligible(eq("event-1"), eq("PROCESSING"), eq("owner"), any(), any(),
                eq(20), eq("PENDING"), any(), eq("PROCESSING"), any())).thenReturn(1);
        when(outboxMapper.findClaimedMessage(eq("event-1"), eq("PROCESSING"), any())).thenReturn(expected);

        ClaimedOutboxMessage result = repository.claimByEventId("event-1", "owner", Duration.ofSeconds(30), 20);

        assertEquals(expected, result);
        verify(outboxMapper).findClaimedMessage(eq("event-1"), eq("PROCESSING"), any());
    }

    /** 过期且次数耗尽的记录才允许收敛为 FAILED；Mapper 更新 0 表示保持现状。 */
    @Test
    void markExhaustedReturnsConditionalUpdateResult() {
        when(outboxMapper.markExhaustedIfEligible(eq("event-1"), eq("FAILED"), eq("ATTEMPTS_EXHAUSTED"),
                eq(20), eq("PENDING"), any(), eq("PROCESSING"), any())).thenReturn(1, 0);

        assertTrue(repository.markExhaustedIfEligible("event-1", 20));
        assertFalse(repository.markExhaustedIfEligible("event-1", 20));
    }

    /** 当前 token 的失败回写在达到领取上限时进入 FAILED，否则按既有退避回到 PENDING。 */
    @Test
    void markFailedKeepsExistingRetryAndTerminalSemantics() {
        when(outboxMapper.markFailed(any(), any(), any(), any(), any(), any())).thenReturn(1);

        assertTrue(repository.markFailed(new ClaimedOutboxMessage("event-retry", "{}", "claim-1", 1), 2,
                "Timeout"));
        assertTrue(repository.markFailed(new ClaimedOutboxMessage("event-terminal", "{}", "claim-2", 2), 2,
                "Timeout"));

        verify(outboxMapper).markFailed(eq("event-retry"), eq(AuthOutboxStatus.PENDING.databaseValue()), any(),
                eq("Timeout"), eq(AuthOutboxStatus.PROCESSING.databaseValue()), eq("claim-1"));
        verify(outboxMapper).markFailed(eq("event-terminal"), eq(AuthOutboxStatus.FAILED.databaseValue()), any(),
                eq("Timeout"), eq(AuthOutboxStatus.PROCESSING.databaseValue()), eq("claim-2"));
    }

    /** 积压快照沿用 Mapper 的计数，并将异常负时长归一化为零。 */
    @Test
    void loadBacklogSnapshotNormalizesNegativeAge() {
        when(outboxMapper.loadBacklogSnapshot(AuthOutboxStatus.PENDING.databaseValue(),
                AuthOutboxStatus.FAILED.databaseValue(), AuthOutboxStatus.PROCESSING.databaseValue()))
                .thenReturn(new OutboxBacklogSnapshot(3, 1, -1));

        assertEquals(new OutboxBacklogSnapshot(3, 1, 0), repository.loadBacklogSnapshot());
    }
}
