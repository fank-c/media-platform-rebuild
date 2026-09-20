package com.calles.platform.recommend.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 推荐微服务 RabbitMQ 消息拓扑配置类 (RecommendMessagingConfiguration)。
 *
 * <p>核心职责：
 * <ul>
 *   <li>声明推荐微服务专属持久化提审消费队列；</li>
 *   <li>绑定至平台统一领域事件 Topic 交换机 {@code media.platform.events}；</li>
 *   <li>订阅视频提审事件路由键 {@code content.video.submitted}。</li>
 * </ul>
 * </p>
 */
@Configuration
public class RecommendMessagingConfiguration {

    /** 推荐微服务提审事件专属消费队列名称。 */
    public static final String VIDEO_SUBMITTED_QUEUE = "recommend-service.video-submitted.v1";

    /** 平台全局媒体业务领域事件 Topic 交换机名称。 */
    public static final String MEDIA_EVENTS_EXCHANGE = "media.platform.events";

    /** 视频提审发布领域事件路由键。 */
    public static final String VIDEO_SUBMITTED_ROUTING_KEY = "content.video.submitted";

    @Bean
    public Queue recommendVideoSubmittedQueue() {
        return new Queue(VIDEO_SUBMITTED_QUEUE, true);
    }

    @Bean
    public TopicExchange recommendMediaEventsExchange() {
        return new TopicExchange(MEDIA_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public Binding recommendVideoSubmittedBinding(Queue recommendVideoSubmittedQueue, TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendVideoSubmittedQueue)
                .to(recommendMediaEventsExchange)
                .with(VIDEO_SUBMITTED_ROUTING_KEY);
    }
}
