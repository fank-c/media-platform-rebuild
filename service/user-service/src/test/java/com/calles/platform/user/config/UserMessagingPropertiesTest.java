package com.calles.platform.user.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** UserMessagingProperties 的启动期参数约束测试。 */
class UserMessagingPropertiesTest {

    /** 默认消费者参数应满足有限重试和单线程消费的安全约束。 */
    @Test
    void validateAcceptsDefaults() {
        assertDoesNotThrow(() -> new UserMessagingProperties().validate());
    }

    /** 零并发会使监听容器无法消费消息，必须拒绝启动。 */
    @Test
    void validateRejectsNonPositiveConcurrency() {
        UserMessagingProperties properties = new UserMessagingProperties();
        properties.setConcurrency(0);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    /** 零次尝试会跳过首个消息处理机会，必须拒绝启动。 */
    @Test
    void validateRejectsNonPositiveMaxAttempts() {
        UserMessagingProperties properties = new UserMessagingProperties();
        properties.setMaxAttempts(0);

        assertThrows(IllegalStateException.class, properties::validate);
    }
}
