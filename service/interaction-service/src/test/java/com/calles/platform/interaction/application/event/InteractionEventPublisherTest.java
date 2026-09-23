package com.calles.platform.interaction.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.calles.platform.interaction.application.outbox.InteractionOutboxDispatchNotifier;
import com.calles.platform.interaction.config.InteractionOutboxProperties;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.infrastructure.outbox.model.InteractionOutboxRecord;
import com.calles.platform.interaction.infrastructure.outbox.persistence.InteractionOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class InteractionEventPublisherTest {

    @Mock
    private InteractionOutboxRepository outboxRepository;

    @Mock
    private InteractionOutboxDispatchNotifier dispatchNotifier;

    private InteractionOutboxProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);

    private InteractionEventPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new InteractionOutboxProperties();
        properties.setEnabled(true);
        publisher = new InteractionEventPublisher(outboxRepository, dispatchNotifier, properties, objectMapper, fixedClock);
    }

    @Test
    @DisplayName("正常发布视频互动事件并验证信封与载荷结构")
    void shouldPublishVideoActionEventCorrectly() throws Exception {
        MDC.put("traceId", "trace-test-123");
        try {
            VideoActionPayload payload = VideoActionPayload.like("user_01", "vid_999");
            publisher.publishVideoAction(payload);

            ArgumentCaptor<InteractionOutboxRecord> recordCaptor = ArgumentCaptor.forClass(InteractionOutboxRecord.class);
            verify(outboxRepository).insert(recordCaptor.capture());

            InteractionOutboxRecord record = recordCaptor.getValue();
            assertThat(record.eventId()).isNotBlank();
            assertThat(record.aggregateId()).isEqualTo("vid_999");
            assertThat(record.eventType()).isEqualTo("interaction.video-action");
            assertThat(record.eventVersion()).isEqualTo(1);
            assertThat(record.traceId()).isEqualTo("trace-test-123");
            assertThat(record.occurredAt()).isEqualTo(Instant.parse("2026-09-23T12:00:00Z"));

            JsonNode root = objectMapper.readTree(record.payload());
            assertThat(root.get("eventId").asText()).isEqualTo(record.eventId());
            assertThat(root.get("eventType").asText()).isEqualTo("interaction.video-action");
            assertThat(root.get("eventVersion").asInt()).isEqualTo(1);
            assertThat(root.get("traceId").asText()).isEqualTo("trace-test-123");
            assertThat(root.get("occurredAt").asText()).isEqualTo("2026-09-23T12:00:00Z");

            JsonNode payloadNode = root.get("payload");
            assertThat(payloadNode.get("userId").asText()).isEqualTo("user_01");
            assertThat(payloadNode.get("vid").asText()).isEqualTo("vid_999");
            assertThat(payloadNode.get("action").asText()).isEqualTo("LIKE");
            assertThat(payloadNode.get("state").asText()).isEqualTo("ACTIVE");

            verify(dispatchNotifier).notifyDispatch(record.eventId());
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("总开关关闭时不产生任何发件箱记录")
    void shouldNotPublishWhenDisabled() {
        properties.setEnabled(false);

        VideoActionPayload payload = VideoActionPayload.star("user_01", "vid_999");
        publisher.publishVideoAction(payload);

        verify(outboxRepository, never()).insert(any());
        verify(dispatchNotifier, never()).notifyDispatch(any());
    }
}
