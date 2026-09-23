package com.calles.platform.interaction.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InteractionOutboxPropertiesTest {

    @Test
    @DisplayName("默认配置合法校验通过")
    void defaultPropertiesShouldPassValidation() {
        InteractionOutboxProperties properties = new InteractionOutboxProperties();
        properties.validate();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isDispatchEnabled()).isFalse();
        assertThat(properties.getBatchSize()).isEqualTo(100);
        assertThat(properties.getMaxAttempts()).isEqualTo(20);
        assertThat(properties.getLease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getConfirmTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("非法 batchSize 校验抛出异常")
    void invalidBatchSizeThrows() {
        InteractionOutboxProperties properties = new InteractionOutboxProperties();
        properties.setBatchSize(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batchSize 必须在 1..1000 之间");
    }

    @Test
    @DisplayName("租约时长小于等于确认超时时间时校验抛出异常")
    void leaseLessThanConfirmTimeoutThrows() {
        InteractionOutboxProperties properties = new InteractionOutboxProperties();
        properties.setConfirmTimeout(Duration.ofSeconds(10));
        properties.setLease(Duration.ofSeconds(5));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约时长 lease 必须严格大于确认超时时长 confirmTimeout");
    }
}
