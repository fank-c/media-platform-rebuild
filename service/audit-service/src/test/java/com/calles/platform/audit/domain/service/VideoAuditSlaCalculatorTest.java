package com.calles.platform.audit.domain.service;

import com.calles.platform.audit.config.aliyun.AliyunGreenProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视频机审 SLA 超时计算器 (VideoAuditSlaCalculator) 单元测试。
 */
class VideoAuditSlaCalculatorTest {

    private AliyunGreenProperties properties;
    private VideoAuditSlaCalculator calculator;

    @BeforeEach
    void setUp() {
        properties = new AliyunGreenProperties();
        properties.setBaseTimeoutSeconds(30);
        properties.setDurationRatio(0.6);
        properties.setMinTimeoutSeconds(45);
        properties.setMaxTimeoutSeconds(600);
        calculator = new VideoAuditSlaCalculator(properties);
    }

    @Test
    @DisplayName("空值或0秒时长：保底至最小超时时限 minTimeout (45s)")
    void shouldClampToMinTimeoutWhenDurationIsZeroOrNull() {
        assertThat(calculator.calculateTimeoutSeconds(null)).isEqualTo(45);
        assertThat(calculator.calculateTimeoutSeconds(0)).isEqualTo(45);
        assertThat(calculator.calculateTimeoutSeconds(-10)).isEqualTo(45);
    }

    @Test
    @DisplayName("普通视频时长 (100秒)：线性计算 (30 + 100 * 0.6 = 90s)")
    void shouldCalculateLinearlyForNormalDuration() {
        // 30 + 100 * 0.6 = 90
        assertThat(calculator.calculateTimeoutSeconds(100)).isEqualTo(90);
    }

    @Test
    @DisplayName("长视频 (1000秒)：熔断夹取至最大超时时限 maxTimeout (600s)")
    void shouldClampToMaxTimeoutForVeryLongVideo() {
        // 30 + 1000 * 0.6 = 630 > 600
        assertThat(calculator.calculateTimeoutSeconds(1000)).isEqualTo(600);
    }

    @Test
    @DisplayName("计算截止时间：在当前时间基础上增加超时预算秒数")
    void shouldCalculateDeadlineBasedOnCurrentTime() {
        Instant before = Instant.now();
        Instant deadline = calculator.calculateDeadline(100);
        Instant after = Instant.now();

        assertThat(deadline).isAfterOrEqualTo(before.plusSeconds(90));
        assertThat(deadline).isBeforeOrEqualTo(after.plusSeconds(90));
    }
}
