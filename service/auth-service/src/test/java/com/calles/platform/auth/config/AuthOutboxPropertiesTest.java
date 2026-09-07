package com.calles.platform.auth.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** AuthOutboxProperties 的启动期参数约束测试。 */
class AuthOutboxPropertiesTest {

    /** 默认参数应满足一次可靠发布任务所需的时序约束。 */
    @Test
    void validateAcceptsDefaults() {
        assertDoesNotThrow(() -> new AuthOutboxProperties().validate());
    }

    /** 租约不长于确认等待时可能导致旧实例覆盖新领取状态，必须拒绝启动。 */
    @Test
    void validateRejectsLeaseNotLongerThanConfirmTimeout() {
        AuthOutboxProperties properties = new AuthOutboxProperties();
        properties.setConfirmTimeout(Duration.ofSeconds(5));
        properties.setLease(Duration.ofSeconds(5));

        assertThrows(IllegalStateException.class, properties::validate);
    }

    /** 批量上限为零会使后台任务空转，必须拒绝启动。 */
    @Test
    void validateRejectsNonPositiveBatchSize() {
        AuthOutboxProperties properties = new AuthOutboxProperties();
        properties.setBatchSize(0);

        assertThrows(IllegalStateException.class, properties::validate);
    }

    /** 有界快速队列超过启动期约束时必须拒绝，避免以无界资源掩盖积压。 */
    @Test
    void validateRejectsOversizedFastDispatchQueue() {
        AuthOutboxProperties properties = new AuthOutboxProperties();
        properties.setFastDispatchQueueCapacity(10_001);

        assertThrows(IllegalStateException.class, properties::validate);
    }
}
