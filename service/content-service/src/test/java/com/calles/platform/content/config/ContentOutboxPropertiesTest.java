package com.calles.platform.content.config;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ContentOutboxProperties 发件箱配置校验单元测试。
 */
@DisplayName("ContentOutboxProperties 配置属性单元测试")
class ContentOutboxPropertiesTest {

    @Test
    @DisplayName("默认配置应合法有效")
    void defaultPropertiesShouldBeValid() {
        ContentOutboxProperties properties = new ContentOutboxProperties();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isFastDispatchEnabled()).isTrue();
        assertThat(properties.getBatchSize()).isEqualTo(100);
        assertThat(properties.getMaxAttempts()).isEqualTo(20);
        assertThat(properties.getLease()).isGreaterThan(properties.getConfirmTimeout());
    }

    @Test
    @DisplayName("非法 batchSize 或 maxAttempts 应抛出异常")
    void invalidBatchSizeOrMaxAttemptsShouldFail() {
        ContentOutboxProperties properties = new ContentOutboxProperties();
        properties.setBatchSize(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batchSize");

        properties.setBatchSize(100);
        properties.setMaxAttempts(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    @DisplayName("租约时长小于等于确认超时时长应抛出异常")
    void leaseMustBeGreaterThanConfirmTimeout() {
        ContentOutboxProperties properties = new ContentOutboxProperties();
        properties.setConfirmTimeout(Duration.ofSeconds(10));
        properties.setLease(Duration.ofSeconds(5));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease");
    }
}
