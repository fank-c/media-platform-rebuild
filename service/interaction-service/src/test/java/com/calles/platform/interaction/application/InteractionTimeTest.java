package com.calles.platform.interaction.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/** 验证服务端 UTC 时钟转换不依赖 JVM 默认时区。 */
class InteractionTimeTest {
    @Test
    void shouldKeepUtcMeaningUnderNonUtcDefaultZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            Clock fixed = Clock.fixed(Instant.parse("2026-09-27T23:59:59.999Z"), ZoneOffset.UTC);
            assertThat(InteractionTime.utcNow(fixed))
                    .isEqualTo(LocalDateTime.of(2026, 9, 27, 23, 59, 59, 999_000_000));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
