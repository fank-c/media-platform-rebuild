package com.calles.platform.interaction.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 互动服务 RabbitMQ 消息拓扑配置。
 *
 * <p>核心职责：
 * <ul>
 *   <li>声明平台全局持久化 Topic 交换机 {@code media.platform.events}，用于发布 {@code interaction.video-action} 领域事件
 *       （点赞 / 收藏 / 分享 / 观看量资格 / 完播共用一条路由）；</li>
 *   <li>声明视频元数据订阅队列与死信出口，消费 content-service 发布的 {@code content.video.metadata} 事件。</li>
 * </ul>
 * </p>
 */
@Configuration
public class InteractionMessagingConfiguration {

    /** 平台全局领域事件 Topic 交换机名称。 */
    public static final String MEDIA_EVENTS_EXCHANGE = "media.platform.events";

    /** content-service 发布的视频元数据事件路由键。 */
    public static final String VIDEO_METADATA_ROUTING_KEY = "content.video.metadata";

    /** 互动服务视频元数据订阅队列名称。 */
    public static final String VIDEO_METADATA_QUEUE = "interaction-service.video-metadata";

    /** 视频元数据死信交换机名称。 */
    public static final String VIDEO_METADATA_DLX = "media.platform.events.dlx";

    /** 视频元数据死信队列名称，承载重试耗尽后的消息，供人工排查与重放。 */
    public static final String VIDEO_METADATA_DLQ = "interaction-service.video-metadata.dlq";

    @Bean
    public TopicExchange platformEventsTopicExchange() {
        return new TopicExchange(MEDIA_EVENTS_EXCHANGE, true, false);
    }

    /**
     * 视频元数据订阅队列。
     *
     * <p>队列显式声明死信交换机与死信路由键：重试耗尽后消息进入死信队列而不是被静默丢弃，
     * 保证"元数据缺失只降级为不计数"这一行为始终可被察觉和补救。</p>
     */
    @Bean
    public Queue interactionVideoMetadataQueue() {
        return QueueBuilder.durable(VIDEO_METADATA_QUEUE)
                .deadLetterExchange(VIDEO_METADATA_DLX)
                .deadLetterRoutingKey(VIDEO_METADATA_QUEUE)
                .build();
    }

    /** 视频元数据死信交换机（直连型，路由键即原队列名）。 */
    @Bean
    public DirectExchange interactionVideoMetadataDeadLetterExchange() {
        return new DirectExchange(VIDEO_METADATA_DLX, true, false);
    }

    /** 视频元数据死信队列。 */
    @Bean
    public Queue interactionVideoMetadataDeadLetterQueue() {
        return QueueBuilder.durable(VIDEO_METADATA_DLQ).build();
    }

    /**
     * 将视频元数据订阅队列绑定至平台领域事件交换机。
     *
     * @param interactionVideoMetadataQueue 视频元数据订阅队列
     * @param platformEventsTopicExchange 平台领域事件交换机
     * @return 订阅绑定
     */
    @Bean
    public Binding interactionVideoMetadataBinding(Queue interactionVideoMetadataQueue,
                                                   TopicExchange platformEventsTopicExchange) {
        return BindingBuilder.bind(interactionVideoMetadataQueue)
                .to(platformEventsTopicExchange)
                .with(VIDEO_METADATA_ROUTING_KEY);
    }

    /**
     * 将死信队列绑定至死信交换机。
     *
     * @param interactionVideoMetadataDeadLetterQueue 死信队列
     * @param interactionVideoMetadataDeadLetterExchange 死信交换机
     * @return 死信绑定
     */
    @Bean
    public Binding interactionVideoMetadataDeadLetterBinding(Queue interactionVideoMetadataDeadLetterQueue,
                                                             DirectExchange interactionVideoMetadataDeadLetterExchange) {
        return BindingBuilder.bind(interactionVideoMetadataDeadLetterQueue)
                .to(interactionVideoMetadataDeadLetterExchange)
                .with(VIDEO_METADATA_QUEUE);
    }
}
