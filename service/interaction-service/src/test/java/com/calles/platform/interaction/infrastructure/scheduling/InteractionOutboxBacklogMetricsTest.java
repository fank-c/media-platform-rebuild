package com.calles.platform.interaction.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.infrastructure.outbox.model.InteractionOutboxStatus;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证关闭派发和清理后仍可观测各状态积压。 */
@ExtendWith(MockitoExtension.class)
class InteractionOutboxBacklogMetricsTest {

    @Mock
    private InteractionOutboxRepository repository;

    @Test
    void shouldSamplePendingAndFailedWithoutDispatch() {
        when(repository.countByStatus(InteractionOutboxStatus.PENDING)).thenReturn(3L);
        when(repository.oldestOccurredAt(InteractionOutboxStatus.PENDING))
                .thenReturn(Instant.parse("2026-09-23T11:59:00Z"));
        when(repository.countByStatus(InteractionOutboxStatus.PROCESSING)).thenReturn(0L);
        when(repository.oldestOccurredAt(InteractionOutboxStatus.PROCESSING)).thenReturn(null);
        when(repository.countByStatus(InteractionOutboxStatus.FAILED)).thenReturn(1L);
        when(repository.oldestOccurredAt(InteractionOutboxStatus.FAILED)).thenReturn(null);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InteractionOutboxBacklogMetrics metrics = new InteractionOutboxBacklogMetrics(repository,
                new InteractionOutboxProperties(),
                Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC), registry);

        metrics.sample();

        assertThat(registry.get("interaction.outbox.backlog.count").tag("status", "PENDING").gauge().value())
                .isEqualTo(3);
        assertThat(registry.get("interaction.outbox.backlog.oldest.age.seconds")
                .tag("status", "PENDING").gauge().value()).isEqualTo(60);
        assertThat(registry.get("interaction.outbox.backlog.count").tag("status", "FAILED").gauge().value())
                .isEqualTo(1);
        assertThat(registry.get("interaction.outbox.backlog.count").tag("status", "PROCESSING").gauge().value())
                .isZero();
    }
}
