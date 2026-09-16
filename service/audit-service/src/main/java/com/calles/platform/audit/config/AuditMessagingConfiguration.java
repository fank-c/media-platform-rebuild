package com.calles.platform.audit.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审核服务 RabbitMQ 消息拓扑配置。
 *
 * <p>声明内容提审事件消费队列、死信交换机和死信队列。</p>
 */
@Configuration
public class AuditMessagingConfiguration {

    public static final String PLATFORM_EXCHANGE = "media.platform.events";
    public static final String AUDIT_QUEUE = "audit-service.content-video-submitted.v1";
    public static final String AUDIT_DLX = "audit-service.dlx";
    public static final String AUDIT_DLQ = "audit-service.content-video-submitted.v1.dlq";
    public static final String ROUTING_KEY_SUBMITTED = "content.video.submitted";
    public static final String ROUTING_KEY_DLQ = "content.video.submitted.failed";

    /** 平台统一领域事件 Topic Exchange。 */
    @Bean
    public TopicExchange auditPlatformEventsExchange() {
        return new TopicExchange(PLATFORM_EXCHANGE, true, false);
    }

    /** 审核服务死信交换机。 */
    @Bean
    public DirectExchange auditDeadLetterExchange() {
        return new DirectExchange(AUDIT_DLX, true, false);
    }

    /** 视频提审事件主消费队列。 */
    @Bean
    public Queue videoSubmittedQueue() {
        return QueueBuilder.durable(AUDIT_QUEUE)
                .deadLetterExchange(AUDIT_DLX)
                .deadLetterRoutingKey(ROUTING_KEY_DLQ)
                .build();
    }

    /** 绑定主消费队列至平台交换机。 */
    @Bean
    public Binding videoSubmittedBinding(Queue videoSubmittedQueue, TopicExchange auditPlatformEventsExchange) {
        return BindingBuilder.bind(videoSubmittedQueue).to(auditPlatformEventsExchange)
                .with(ROUTING_KEY_SUBMITTED);
    }

    /** 审核失败死信队列。 */
    @Bean
    public Queue videoSubmittedDeadLetterQueue() {
        return QueueBuilder.durable(AUDIT_DLQ).build();
    }

    /** 绑定死信队列至死信交换机。 */
    @Bean
    public Binding videoSubmittedDeadLetterBinding(Queue videoSubmittedDeadLetterQueue,
                                                  DirectExchange auditDeadLetterExchange) {
        return BindingBuilder.bind(videoSubmittedDeadLetterQueue).to(auditDeadLetterExchange)
                .with(ROUTING_KEY_DLQ);
    }
}
