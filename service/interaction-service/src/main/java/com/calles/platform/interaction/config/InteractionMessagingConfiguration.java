package com.calles.platform.interaction.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 互动服务 RabbitMQ 消息拓扑配置。
 *
 * <p>声明平台全局持久化 Topic 交换机 {@code media.platform.events}，用于发布 {@code interaction.video-action.v1} 领域事件。</p>
 */
@Configuration
public class InteractionMessagingConfiguration {

    public static final String MEDIA_EVENTS_EXCHANGE = "media.platform.events";
    public static final String VIDEO_ACTION_ROUTING_KEY = "interaction.video-action.v1";

    @Bean
    public TopicExchange platformEventsTopicExchange() {
        return new TopicExchange(MEDIA_EVENTS_EXCHANGE, true, false);
    }
}
