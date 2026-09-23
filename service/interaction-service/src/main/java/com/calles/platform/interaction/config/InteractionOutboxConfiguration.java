package com.calles.platform.interaction.config;

import com.calles.platform.interaction.application.outbox.InteractionOutboxDispatchNotifier;
import com.calles.platform.interaction.infrastructure.outbox.dispatch.InteractionOutboxDispatcher;
import com.calles.platform.interaction.infrastructure.outbox.notify.AfterCommitInteractionOutboxDispatchNotifier;
import com.calles.platform.interaction.infrastructure.outbox.notify.NoopInteractionOutboxDispatchNotifier;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Interaction Outbox 发件箱核心基础设施 Bean 配置类。
 */
@Configuration
public class InteractionOutboxConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 基于 Java 21 虚拟线程的提交后 Outbox 快速发送执行器。
     */
    @Bean(name = "interactionOutboxFastDispatchExecutor")
    @ConditionalOnProperty(prefix = "interaction.outbox", name = {"enabled", "fast-dispatch-enabled"}, havingValue = "true")
    public TaskExecutor interactionOutboxFastDispatchExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("interaction-outbox-fast-vt-");
        executor.setVirtualThreads(true);
        return executor;
    }

    /**
     * 事务提交后快速分发通知器。
     */
    @Bean
    @ConditionalOnProperty(prefix = "interaction.outbox", name = {"enabled", "fast-dispatch-enabled"}, havingValue = "true")
    public InteractionOutboxDispatchNotifier interactionOutboxDispatchNotifier(
            @Qualifier("interactionOutboxFastDispatchExecutor") TaskExecutor executor,
            InteractionOutboxDispatcher dispatcher) {
        return new AfterCommitInteractionOutboxDispatchNotifier(executor, dispatcher);
    }

    /**
     * 快速通道关闭时的降级空通知器。
     */
    @Bean
    @ConditionalOnMissingBean(InteractionOutboxDispatchNotifier.class)
    public InteractionOutboxDispatchNotifier noopInteractionOutboxDispatchNotifier() {
        return new NoopInteractionOutboxDispatchNotifier();
    }
}
