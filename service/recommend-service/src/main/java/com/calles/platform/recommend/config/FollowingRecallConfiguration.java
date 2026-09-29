package com.calles.platform.recommend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 关注召回基础 Bean 配置。
 */
@Configuration
public class FollowingRecallConfiguration {

    /**
     * 提供统一系统时钟，便于固定时间锚点并在测试中注入固定时钟。
     *
     * @return 当前默认时区系统时钟
     */
    @Bean
    public Clock followingRecallClock() {
        return Clock.systemDefaultZone();
    }
}
