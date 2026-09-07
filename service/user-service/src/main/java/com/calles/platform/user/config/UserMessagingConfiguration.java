package com.calles.platform.user.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用户资料事件消费拓扑，显式声明主队列、死信交换机和永久死信队列。
 */
@Configuration
public class UserMessagingConfiguration {

    /** 平台领域事件 Topic Exchange。 */
    @Bean
    public TopicExchange userPlatformEventsExchange() {
        return new TopicExchange("media.platform.events", true, false);
    }

    /** user-service 独占失败交换机。 */
    @Bean
    public DirectExchange userDeadLetterExchange() {
        return new DirectExchange("user-service.dlx", true, false);
    }

    /** 账号创建消费队列；拒绝且不 requeue 的消息进入永久死信队列。 */
    @Bean
    public Queue accountCreatedQueue() {
        return QueueBuilder.durable("user-service.auth-account-created.v1")
                .deadLetterExchange("user-service.dlx")
                .deadLetterRoutingKey("auth.account.created.v1.failed")
                .build();
    }

    /** 将账号创建 routing key 绑定到用户消费队列。 */
    @Bean
    public Binding accountCreatedBinding(Queue accountCreatedQueue, TopicExchange userPlatformEventsExchange) {
        return BindingBuilder.bind(accountCreatedQueue).to(userPlatformEventsExchange)
                .with("auth.account.created.v1");
    }

    /** 永久保留失败消息，不设置自动过期或自动删除。 */
    @Bean
    public Queue accountCreatedDeadLetterQueue() {
        return QueueBuilder.durable("user-service.auth-account-created.v1.dlq").build();
    }

    /** 将失败路由键绑定到死信队列。 */
    @Bean
    public Binding accountCreatedDeadLetterBinding(Queue accountCreatedDeadLetterQueue,
            DirectExchange userDeadLetterExchange) {
        return BindingBuilder.bind(accountCreatedDeadLetterQueue).to(userDeadLetterExchange)
                .with("auth.account.created.v1.failed");
    }
}
