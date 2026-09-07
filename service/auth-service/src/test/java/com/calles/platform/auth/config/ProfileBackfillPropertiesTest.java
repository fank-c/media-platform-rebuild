package com.calles.platform.auth.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** ProfileBackfillProperties 的启动期参数约束测试。 */
class ProfileBackfillPropertiesTest {

    /** 默认的关闭和 dry-run 参数应能安全通过校验。 */
    @Test
    void validateAcceptsDefaults() {
        assertDoesNotThrow(() -> new ProfileBackfillProperties().validate());
    }

    /** 非正轮询间隔会使调度器无法按预期工作，必须拒绝启动。 */
    @Test
    void validateRejectsNonPositivePollInterval() {
        ProfileBackfillProperties properties = new ProfileBackfillProperties();
        properties.setPollInterval(Duration.ZERO);

        assertThrows(IllegalStateException.class, properties::validate);
    }
}
