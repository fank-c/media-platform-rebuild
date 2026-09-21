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

    /** 推荐微服务正式发布入池专属消费队列名称。 */
    public static final String VIDEO_PUBLISHED_QUEUE = "recommend-service.video-published.v1";

    /** 视频公开发布领域事件路由键。 */
    public static final String VIDEO_PUBLISHED_ROUTING_KEY = "content.video.published";

    /** 推荐微服务视频生命周期清退专属消费队列名称。 */
    public static final String VIDEO_LIFECYCLE_QUEUE = "recommend-service.video-lifecycle.v1";

    /** 视频下架领域事件路由键。 */
    public static final String VIDEO_OFFLINED_ROUTING_KEY = "content.video.offlined";

    /** 视频封禁领域事件路由键。 */
    public static final String VIDEO_BANNED_ROUTING_KEY = "content.video.banned";

    @Bean
    public Queue recommendVideoSubmittedQueue() {
        return new Queue(VIDEO_SUBMITTED_QUEUE, true);
    }

    @Bean
    public Queue recommendVideoPublishedQueue() {
        return new Queue(VIDEO_PUBLISHED_QUEUE, true);
    }

    @Bean
    public Queue recommendVideoLifecycleQueue() {
        return new Queue(VIDEO_LIFECYCLE_QUEUE, true);
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

    @Bean
    public Binding recommendVideoPublishedBinding(Queue recommendVideoPublishedQueue, TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendVideoPublishedQueue)
                .to(recommendMediaEventsExchange)
                .with(VIDEO_PUBLISHED_ROUTING_KEY);
    }

    @Bean
    public Binding recommendVideoOfflinedBinding(Queue recommendVideoLifecycleQueue, TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendVideoLifecycleQueue)
                .to(recommendMediaEventsExchange)
                .with(VIDEO_OFFLINED_ROUTING_KEY);
    }

    @Bean
    public Binding recommendVideoBannedBinding(Queue recommendVideoLifecycleQueue, TopicExchange recommendMediaEventsExchange) {
        return BindingBuilder.bind(recommendVideoLifecycleQueue)
                .to(recommendMediaEventsExchange)
                .with(VIDEO_BANNED_ROUTING_KEY);
    }
}
