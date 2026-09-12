package com.calles.platform.auth.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.calles.platform.auth.domain.account.AuthAccount;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** 账号创建事件最小披露与时间契约测试。 */
class AccountCreatedEventFactoryTest {

    /** 事件只允许包含资料初始化所需字段，不得泄露邮箱或密码摘要。 */
    @Test
    void eventDoesNotExposeAuthenticationCredentials() throws Exception {
        AuthAccount account = new AuthAccount();
        account.setId("0123456789abcdef0123456789abcdef");
        account.setEmail("sensitive@test.com");
        account.setPasswordHash("sensitive-password-hash");
        account.setCreatedAt(LocalDateTime.of(2026, 9, 1, 8, 30));
        Clock clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper();

        AuthOutboxRecord record = new AccountCreatedEventFactory(objectMapper, clock).create(account);
        JsonNode root = objectMapper.readTree(record.payload());

        assertEquals("auth.account.created", root.path("eventType").asText());
        assertEquals(1, root.path("version").asInt());
        assertEquals("2026-09-05T00:00:00Z", root.path("occurredAt").asText());
        assertEquals("auth-service", root.path("producer").asText());
        assertEquals(account.getId(), root.path("aggregateId").asText());
        assertEquals(account.getId(), root.path("payload").path("accountId").asText());
        assertEquals("user", root.path("payload").path("accountType").asText());
        assertEquals("2026-09-01T08:30:00Z", root.path("payload").path("createdAt").asText());
        assertFalse(record.payload().contains("sensitive@test.com"));
        assertFalse(record.payload().contains("sensitive-password-hash"));
        assertFalse(root.path("payload").has("email"));
        assertFalse(root.path("payload").has("passwordHash"));
    }

    /** 缺失请求链路时以稳定 eventId 作为 traceId，保证 Outbox 重试复用同一追踪标识。 */
    @Test
    void missingMdcTraceIdFallsBackToEventId() throws Exception {
        AuthAccount account = new AuthAccount();
        account.setId("0123456789abcdef0123456789abcdef");
        Clock clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper();
        MDC.remove("traceId");

        AuthOutboxRecord record = new AccountCreatedEventFactory(objectMapper, clock).create(account);
        JsonNode root = objectMapper.readTree(record.payload());

        assertEquals(record.eventId(), record.traceId());
        assertEquals(record.eventId(), root.path("traceId").asText());
    }
}
