package com.calles.platform.interaction.infrastructure.outbox.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.interaction.infrastructure.outbox.model.InteractionOutboxRecord;
import java.sql.Timestamp;
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

@ExtendWith(MockitoExtension.class)
class InteractionOutboxRepositoryTest {

    @Mock
    private InteractionOutboxMapper mapper;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);
    private InteractionOutboxRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InteractionOutboxRepository(mapper, clock);
    }

    @Test
    @DisplayName("插入发件箱记录调用Mapper insert")
    void shouldInsertRecord() {
        InteractionOutboxRecord record = InteractionOutboxRecord.of(
                "evt_01", "vid_100", "interaction.video-action", "{}", "trace_01", clock.instant()
        );

        repository.insert(record);

        verify(mapper).insert(eq(record), any(Timestamp.class), eq("PENDING"), any(Timestamp.class));
    }

    @Test
    @DisplayName("CAS 认领成功返回快照")
    void shouldClaimWhenEligible() {
        when(mapper.markClaimedIfEligible(anyString(), anyString(), anyString(), any(), anyString(), anyInt(), anyString(), any(), anyString(), any()))
                .thenReturn(1);

        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt_01", "vid_100", "interaction.video-action", 1, "{}", "trace_01", clock.instant(), 1, "tok"
        );
        when(mapper.findClaimedMessage(eq("evt_01"), eq("PROCESSING"), anyString()))
                .thenReturn(message);

        ClaimedOutboxMessage result = repository.claimByEventId("evt_01", "owner_1", Duration.ofSeconds(30), 20);

        assertThat(result).isNotNull();
        assertThat(result.eventId()).isEqualTo("evt_01");
    }

    @Test
    @DisplayName("CAS 抢占失败返回 null")
    void shouldReturnNullWhenClaimFailed() {
        when(mapper.markClaimedIfEligible(anyString(), anyString(), anyString(), any(), anyString(), anyInt(), anyString(), any(), anyString(), any()))
                .thenReturn(0);

        ClaimedOutboxMessage result = repository.claimByEventId("evt_01", "owner_1", Duration.ofSeconds(30), 20);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("发布成功回写 PUBLISHED 状态")
    void shouldMarkPublished() {
        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt_01", "vid_100", "interaction.video-action", 1, "{}", "trace_01", clock.instant(), 1, "tok"
        );
        when(mapper.markPublished(eq("evt_01"), eq("PUBLISHED"), any(), eq("PROCESSING"), eq("tok")))
                .thenReturn(1);

        boolean ok = repository.markPublished(message);

        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("投递失败回写重试退避时间并释放租约")
    void shouldMarkFailedWithBackoff() {
        ClaimedOutboxMessage message = new ClaimedOutboxMessage(
                "evt_01", "vid_100", "interaction.video-action", 1, "{}", "trace_01", clock.instant(), 2, "tok"
        );
        when(mapper.markFailed(eq("evt_01"), eq("PENDING"), any(), eq("ERROR_CODE"), eq("PROCESSING"), eq("tok")))
                .thenReturn(1);

        boolean ok = repository.markFailed(message, 20, "ERROR_CODE");

        assertThat(ok).isTrue();
    }
}
