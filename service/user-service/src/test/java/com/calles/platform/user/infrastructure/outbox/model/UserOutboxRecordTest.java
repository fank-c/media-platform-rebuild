package com.calles.platform.user.infrastructure.outbox.model;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("UserOutboxRecord 模型工厂测试")
class UserOutboxRecordTest {

    @Test
    @DisplayName("工厂方法能够正确使用 MDC 中的 traceId")
    void ofWithMdcTraceId() {
        MDC.put("traceId", "trace-test-1234");
        try {
            Instant now = Instant.now();
            UserOutboxRecord record = UserOutboxRecord.of("user_1001", "interaction.author-action", "{}", now);
            assertNotNull(record.eventId());
            assertEquals("user_1001", record.aggregateId());
            assertEquals("interaction.author-action", record.eventType());
            assertEquals(1, record.eventVersion());
            assertEquals("trace-test-1234", record.traceId());
            assertEquals(now, record.occurredAt());
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("MDC 无 traceId 时安全回退至 eventId")
    void ofWithoutMdcTraceId() {
        MDC.clear();
        Instant now = Instant.now();
        UserOutboxRecord record = UserOutboxRecord.of("user_1001", "interaction.author-action", "{}", now);
        assertNotNull(record.eventId());
        assertEquals(record.eventId(), record.traceId());
    }
}
