package com.calles.platform.transcode.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 视频转码微服务 RabbitMQ 消息拓扑配置类 (TranscodeMessagingConfiguration)。
 *
 * <p>核心职责：
 * <ul>
 *   <li>声明转码微服务专属持久化消费队列；</li>
 *   <li>绑定至平台全局领域事件 Topic 交换机 {@code media.platform.events}；</li>
 *   <li>订阅视频提审发布事件路由键 {@code content.video.submitted}。</li>
 * </ul>
 * </p>
 */
@Configuration
public class TranscodeMessagingConfiguration {

    /** 转码服务提审事件专属消费队列名。 */
    public static final String TRANSCODE_QUEUE = "transcode-service.video-submitted.v1";

    /** 平台全局媒体业务领域事件 Topic 交换机名。 */
    public static final String MEDIA_EVENTS_EXCHANGE = "media.platform.events";

    /** 视频提审发布领域事件路由键。 */
    public static final String VIDEO_SUBMITTED_ROUTING_KEY = "content.video.submitted";

    @Bean
    public Queue transcodeVideoSubmittedQueue() {
        return new Queue(TRANSCODE_QUEUE, true);
    }

    @Bean
    public TopicExchange transcodeMediaEventsExchange() {
        return new TopicExchange(MEDIA_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public Binding transcodeVideoSubmittedBinding(Queue transcodeVideoSubmittedQueue, TopicExchange transcodeMediaEventsExchange) {
        return BindingBuilder.bind(transcodeVideoSubmittedQueue)
                .to(transcodeMediaEventsExchange)
                .with(VIDEO_SUBMITTED_ROUTING_KEY);
    }
}
