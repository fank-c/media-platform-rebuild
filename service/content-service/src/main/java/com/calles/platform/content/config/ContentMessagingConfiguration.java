package com.calles.platform.content.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 内容微服务 RabbitMQ 消息通信拓扑配置类。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：发布端消息拓扑声明；</li>
 *   <li><b>声明对象</b>：平台统一持久化 Topic 交换机 {@code media.platform.events}；</li>
 *   <li><b>解耦原则</b>：只声明事件发布所必需的 Exchange，不侵入下游微服务私有的消费队列。</li>
 * </ul>
 * </p>
 */
@Configuration
public class ContentMessagingConfiguration {

    /** 平台统一媒体业务领域事件 Topic 交换机名。 */
    public static final String MEDIA_EVENTS_EXCHANGE = "media.platform.events";

    /**
     * 声明持久化 Topic Exchange，供全平台领域事件按 Routing Key 灵活分发路由。
     *
     * @return 平台统一事件 Topic 交换机 Bean
     */
    @Bean
    public TopicExchange contentPlatformEventsExchange() {
        return new TopicExchange(MEDIA_EVENTS_EXCHANGE, true, false);
    }
}
