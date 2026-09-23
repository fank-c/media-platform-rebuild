package com.calles.platform.user.infrastructure.outbox.persistence;

import com.calles.platform.user.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.user.infrastructure.outbox.model.UserOutboxRecord;
import com.calles.platform.user.infrastructure.persistence.mapper.outbox.UserOutboxMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserOutboxRepository 仓储调度测试")
class UserOutboxRepositoryTest {

    @Mock
    private UserOutboxMapper outboxMapper;

    private Clock clock;
    private UserOutboxRepository repository;

    private static final Instant FIXED_NOW = Instant.parse("2026-09-22T10:00:00Z");

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
        repository = new UserOutboxRepository(outboxMapper, clock);
    }

    @Test
    @DisplayName("insert 应正确调用 mapper 插入初始 PENDING 记录")
    void testInsert() {
        UserOutboxRecord record = new UserOutboxRecord(
                "evt-101", "user_1", "interaction.author-action", 1, "{}", "tr-1", FIXED_NOW
        );
        repository.insert(record);
        verify(outboxMapper).insert(eq(record), any(), eq("PENDING"), any());
    }

    @Test
    @DisplayName("findClaimableEventIds 应委托 mapper 查找待处理事件")
    void testFindClaimableEventIds() {
        when(outboxMapper.findClaimableEventIds(anyString(), any(), anyString(), any(), anyInt(), anyInt()))
                .thenReturn(List.of("evt-1", "evt-2"));

        List<String> result = repository.findClaimableEventIds(10, 5);
        assertEquals(2, result.size());
        assertEquals("evt-1", result.get(0));
    }

    @Test
    @DisplayName("claimByEventId 成功占用租约返回不可变快照")
    void testClaimSuccess() {
        when(outboxMapper.markClaimedIfEligible(anyString(), anyString(), anyString(), any(), anyString(), anyInt(), anyString(), any(), anyString(), any()))
                .thenReturn(1);
        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt-1", "user_1", "interaction.author-action", 1, "{}", "tr-1", FIXED_NOW, 1, "token-xyz"
        );
        when(outboxMapper.findClaimedMessage(eq("evt-1"), anyString(), anyString()))
                .thenReturn(message);

        ClaimedOutboxMessage claimed = repository.claimByEventId("evt-1", "owner-node", Duration.ofSeconds(30), 5);
        assertNotNull(claimed);
        assertEquals("evt-1", claimed.eventId());
        assertEquals("token-xyz", claimed.claimToken());
    }

    @Test
    @DisplayName("claimByEventId 竞争失败返回 null")
    void testClaimFail() {
        when(outboxMapper.markClaimedIfEligible(anyString(), anyString(), anyString(), any(), anyString(), anyInt(), anyString(), any(), anyString(), any()))
                .thenReturn(0);

        ClaimedOutboxMessage claimed = repository.claimByEventId("evt-1", "owner-node", Duration.ofSeconds(30), 5);
        assertNull(claimed);
    }

    @Test
    @DisplayName("markPublished 应回写 PUBLISHED 状态")
    void testMarkPublished() {
        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt-1", "user_1", "interaction.author-action", 1, "{}", "tr-1", FIXED_NOW, 1, "token-xyz"
        );
        when(outboxMapper.markPublished(eq("evt-1"), eq("PUBLISHED"), any(), eq("PROCESSING"), eq("token-xyz")))
                .thenReturn(1);

        boolean ok = repository.markPublished(message);
        assertTrue(ok);
    }

    @Test
    @DisplayName("markFailed 在未耗尽次数时保留 PENDING 并计算退避")
    void testMarkFailedRetrying() {
        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt-1", "user_1", "interaction.author-action", 1, "{}", "tr-1", FIXED_NOW, 2, "token-xyz"
        );
        when(outboxMapper.markFailed(eq("evt-1"), eq("PENDING"), any(), eq("TimeoutException"), eq("PROCESSING"), eq("token-xyz")))
                .thenReturn(1);

        boolean ok = repository.markFailed(message, 5, "TimeoutException");
        assertTrue(ok);
    }

    @Test
    @DisplayName("markFailed 在达到最大次数时收敛为 FAILED 终态")
    void testMarkFailedExhausted() {
        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt-1", "user_1", "interaction.author-action", 1, "{}", "tr-1", FIXED_NOW, 5, "token-xyz"
        );
        when(outboxMapper.markFailed(eq("evt-1"), eq("FAILED"), any(), eq("TimeoutException"), eq("PROCESSING"), eq("token-xyz")))
                .thenReturn(1);

        boolean ok = repository.markFailed(message, 5, "TimeoutException");
        assertTrue(ok);
    }
}
