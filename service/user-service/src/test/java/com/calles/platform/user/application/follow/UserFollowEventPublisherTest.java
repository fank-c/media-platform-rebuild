package com.calles.platform.user.application.follow;

import com.calles.platform.user.application.outbox.UserOutboxDispatchNotifier;
import com.calles.platform.user.infrastructure.outbox.model.UserOutboxRecord;
import com.calles.platform.user.infrastructure.outbox.persistence.UserOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserFollowEventPublisher 事件发布与 Outbox 写入测试")
class UserFollowEventPublisherTest {

    @Mock
    private UserOutboxRepository outboxRepository;

    @Mock
    private UserOutboxDispatchNotifier dispatchNotifier;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-22T10:00:00Z"), ZoneId.of("UTC"));

    private UserFollowEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new UserFollowEventPublisher(outboxRepository, dispatchNotifier, objectMapper, clock);
    }

    @Test
    @DisplayName("发布关注事件应生成标准 interaction.author-action 载荷并写入 Outbox")
    void testPublishFollowedEvent() throws Exception {
        publisher.publishFollowedEvent("u001", "u002");

        ArgumentCaptor<UserOutboxRecord> recordCaptor = ArgumentCaptor.forClass(UserOutboxRecord.class);
        verify(outboxRepository).insert(recordCaptor.capture());

        UserOutboxRecord record = recordCaptor.getValue();
        assertEquals("u001", record.aggregateId());
        assertEquals("interaction.author-action", record.eventType());
        assertEquals(1, record.eventVersion());

        JsonNode jsonNode = objectMapper.readTree(record.payload());
        assertEquals(record.eventId(), jsonNode.get("eventId").asText());
        assertEquals("interaction.author-action", jsonNode.get("eventType").asText());
        assertEquals(1, jsonNode.get("eventVersion").asInt());
        assertEquals("2026-09-22T10:00:00Z", jsonNode.get("occurredAt").asText());

        JsonNode payloadNode = jsonNode.get("payload");
        assertEquals("u001", payloadNode.get("userId").asText());
        assertEquals("u002", payloadNode.get("authorId").asText());
        assertEquals("FOLLOW", payloadNode.get("action").asText());
        assertEquals("ACTIVE", payloadNode.get("state").asText());

        verify(dispatchNotifier).notifyDispatch(record.eventId());
    }

    @Test
    @DisplayName("发布取关事件应生成 state=INACTIVE 的载荷并写入 Outbox")
    void testPublishUnfollowedEvent() throws Exception {
        publisher.publishUnfollowedEvent("u001", "u002");

        ArgumentCaptor<UserOutboxRecord> recordCaptor = ArgumentCaptor.forClass(UserOutboxRecord.class);
        verify(outboxRepository).insert(recordCaptor.capture());

        UserOutboxRecord record = recordCaptor.getValue();
        JsonNode jsonNode = objectMapper.readTree(record.payload());
        JsonNode payloadNode = jsonNode.get("payload");
        assertEquals("u001", payloadNode.get("userId").asText());
        assertEquals("u002", payloadNode.get("authorId").asText());
        assertEquals("FOLLOW", payloadNode.get("action").asText());
        assertEquals("INACTIVE", payloadNode.get("state").asText());

        verify(dispatchNotifier).notifyDispatch(record.eventId());
    }
}
