package com.calles.platform.transcode.infrastructure.concurrency;

import com.calles.platform.transcode.config.TranscodeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 转码并发保护限流器 (TranscodeRateLimiter) 单元测试。
 */
class TranscodeRateLimiterTest {

    @Test
    @DisplayName("限流器正常执行动作并释放许可")
    void executeWithPermit_success() {
        TranscodeProperties props = new TranscodeProperties();
        props.setMaxConcurrentTasks(2);

        TranscodeRateLimiter limiter = new TranscodeRateLimiter(props);
        limiter.init();

        assertEquals(2, limiter.getAvailablePermits());

        AtomicInteger counter = new AtomicInteger(0);
        String result = limiter.executeWithPermit(() -> {
            counter.incrementAndGet();
            assertEquals(1, limiter.getAvailablePermits());
            return "SUCCESS";
        });

        assertEquals("SUCCESS", result);
        assertEquals(1, counter.get());
        assertEquals(2, limiter.getAvailablePermits());
    }
}
