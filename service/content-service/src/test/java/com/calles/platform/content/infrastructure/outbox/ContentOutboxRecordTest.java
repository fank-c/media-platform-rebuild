package com.calles.platform.content.infrastructure.outbox;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContentOutboxRecord 发件箱记录不可变实体与链路追踪工厂方法单元测试。
 */
@DisplayName("ContentOutboxRecord 链路追踪与工厂构建测试")
class ContentOutboxRecordTest {

    @AfterEach
    void tearDown() {
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("当 MDC 存在 traceId 时，工厂方法优先复用当前链路追踪标识")
    void shouldResolveTraceIdFromMdcWhenPresent() {
        MDC.put("traceId", "trace-req-abc-123");
        Instant now = Instant.now();

        ContentOutboxRecord record = ContentOutboxRecord.of(
                "v_100",
                "content.video.submitted",
                "{\"videoId\":\"v_100\"}",
                now
        );

        assertThat(record.eventId()).isNotNull().hasSize(32);
        assertThat(record.aggregateId()).isEqualTo("v_100");
        assertThat(record.eventType()).isEqualTo("content.video.submitted");
        assertThat(record.eventVersion()).isEqualTo(1);
        assertThat(record.traceId()).isEqualTo("trace-req-abc-123");
        assertThat(record.occurredAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("当 MDC 无 traceId 或为空白字符串时，工厂方法回退使用 eventId 作为稳定追踪标识")
    void shouldFallbackToEventIdWhenMdcTraceIdAbsentOrBlank() {
        MDC.remove("traceId");
        Instant now = Instant.now();

        ContentOutboxRecord record1 = ContentOutboxRecord.of(
                "v_100",
                "content.video.published",
                "{\"videoId\":\"v_100\"}",
                now
        );

        assertThat(record1.eventId()).isNotNull();
        assertThat(record1.traceId()).isEqualTo(record1.eventId());

        MDC.put("traceId", "   ");
        ContentOutboxRecord record2 = ContentOutboxRecord.of(
                "v_100",
                "content.video.rejected",
                "{\"videoId\":\"v_100\"}",
                now
        );

        assertThat(record2.eventId()).isNotNull();
        assertThat(record2.traceId()).isEqualTo(record2.eventId());
    }
}
