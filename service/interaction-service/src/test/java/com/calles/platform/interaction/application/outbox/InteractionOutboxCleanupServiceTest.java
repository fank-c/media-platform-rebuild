package com.calles.platform.interaction.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;

import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InteractionOutboxCleanupServiceTest {

    @Mock
    private InteractionOutboxRepository repository;

    @Test
    void shouldDeletePublishedRecordsInBoundedBatches() {
        InteractionOutboxProperties properties = new InteractionOutboxProperties();
        properties.setCleanupEnabled(true);
        properties.setRetention(Duration.ofDays(30));
        properties.setCleanupBatchSize(2);
        properties.setCleanupMaxBatches(2);
        when(repository.deletePublishedBefore(Instant.parse("2026-08-24T12:00:00Z"), 2))
                .thenReturn(2, 1);

        InteractionOutboxCleanupService service = new InteractionOutboxCleanupService(
                repository, properties, Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());

        assertThat(service.cleanup()).isEqualTo(3);
        verify(repository, times(2)).deletePublishedBefore(Instant.parse("2026-08-24T12:00:00Z"), 2);
    }

    @Test
    void shouldNotDeleteWhenDisabled() {
        InteractionOutboxProperties properties = new InteractionOutboxProperties();
        InteractionOutboxCleanupService service = new InteractionOutboxCleanupService(
                repository, properties, Clock.systemUTC(), new SimpleMeterRegistry());

        assertThat(service.cleanup()).isZero();
        verify(repository, never()).deletePublishedBefore(any(), anyInt());
    }

    @Test
    void shouldCountFailureAndPartialDeletion() {
        InteractionOutboxProperties properties = new InteractionOutboxProperties();
        properties.setCleanupEnabled(true);
        properties.setCleanupBatchSize(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Instant cutoff = Instant.parse("2026-08-24T12:00:00Z");
        when(repository.deletePublishedBefore(cutoff, 2))
                .thenReturn(2).thenThrow(new IllegalStateException("db unavailable"));
        InteractionOutboxCleanupService service = new InteractionOutboxCleanupService(repository, properties,
                Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC), registry);

        assertThatThrownBy(service::cleanup).isInstanceOf(IllegalStateException.class);
        assertThat(registry.counter("interaction.outbox.cleanup.deleted").count()).isEqualTo(2);
        assertThat(registry.counter("interaction.outbox.cleanup.failures").count()).isEqualTo(1);
        assertThat(registry.timer("interaction.outbox.cleanup.duration").count()).isEqualTo(1);
    }

    @Test
    void shouldStopAtMaximumBatches() {
        InteractionOutboxProperties properties = new InteractionOutboxProperties();
        properties.setCleanupEnabled(true);
        properties.setCleanupBatchSize(1);
        properties.setCleanupMaxBatches(2);
        Instant cutoff = Instant.parse("2026-08-24T12:00:00Z");
        when(repository.deletePublishedBefore(cutoff, 1)).thenReturn(1);
        InteractionOutboxCleanupService service = new InteractionOutboxCleanupService(repository, properties,
                Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC), new SimpleMeterRegistry());

        assertThat(service.cleanup()).isEqualTo(2);
        verify(repository, times(2)).deletePublishedBefore(cutoff, 1);
    }
}
