package com.calles.platform.auth.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 认证事件发布拓扑，只声明平台领域交换机，不持有 user-service 的消费队列。
 */
@Configuration
public class AuthMessagingConfiguration {

    /** 声明持久化 Topic Exchange，供版本化领域事件按 routing key 分发。 */
    @Bean
    public TopicExchange platformEventsExchange() {
        return new TopicExchange("media.platform.events", true, false);
    }
}
