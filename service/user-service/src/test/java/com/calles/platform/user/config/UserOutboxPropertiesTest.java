package com.calles.platform.user.config;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UserOutboxProperties 参数配置校验测试")
class UserOutboxPropertiesTest {

    @Test
    @DisplayName("默认参数应合法且能成功通过校验")
    void defaultPropertiesShouldBeValid() {
        UserOutboxProperties properties = new UserOutboxProperties();
        assertDoesNotThrow(properties::validate);
        assertTrue(properties.isEnabled());
        assertTrue(properties.isFastDispatchEnabled());
        assertEquals(100, properties.getBatchSize());
        assertEquals(20, properties.getMaxAttempts());
    }

    @Test
    @DisplayName("非法 batchSize 或 maxAttempts 抛出异常")
    void invalidBatchSizeOrAttemptsShouldFail() {
        UserOutboxProperties properties = new UserOutboxProperties();
        properties.setBatchSize(0);
        assertThrows(IllegalStateException.class, properties::validate);

        properties.setBatchSize(100);
        properties.setMaxAttempts(0);
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    @DisplayName("租约必须大于确认超时时间")
    void leaseMustBeGreaterThanConfirmTimeout() {
        UserOutboxProperties properties = new UserOutboxProperties();
        properties.setConfirmTimeout(Duration.ofSeconds(10));
        properties.setLease(Duration.ofSeconds(5));
        assertThrows(IllegalStateException.class, properties::validate);
    }
}
