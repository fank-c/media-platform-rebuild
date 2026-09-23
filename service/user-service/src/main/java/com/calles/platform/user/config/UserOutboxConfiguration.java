package com.calles.platform.user.config;

import com.calles.platform.user.application.outbox.UserOutboxDispatchNotifier;
import com.calles.platform.user.infrastructure.outbox.dispatch.UserOutboxDispatcher;
import com.calles.platform.user.infrastructure.outbox.notify.AfterCommitUserOutboxDispatchNotifier;
import com.calles.platform.user.infrastructure.outbox.notify.NoopUserOutboxDispatchNotifier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * User Outbox 发件箱核心基础设施 Bean 配置类。
 */
@Configuration
public class UserOutboxConfiguration {

    /**
     * 基于 Java 21 虚拟线程的提交后 Outbox 快速发送执行器。
     */
    @Bean(name = "userOutboxFastDispatchExecutor")
    @ConditionalOnProperty(prefix = "user.outbox", name = {"enabled", "fast-dispatch-enabled"}, havingValue = "true", matchIfMissing = true)
    public TaskExecutor userOutboxFastDispatchExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("user-outbox-fast-vt-");
        executor.setVirtualThreads(true);
        return executor;
    }

    /**
     * 事务提交后快速分发通知器。
     */
    @Bean
    @ConditionalOnProperty(prefix = "user.outbox", name = {"enabled", "fast-dispatch-enabled"}, havingValue = "true", matchIfMissing = true)
    public UserOutboxDispatchNotifier userOutboxDispatchNotifier(
            @Qualifier("userOutboxFastDispatchExecutor") TaskExecutor executor,
            UserOutboxDispatcher dispatcher) {
        return new AfterCommitUserOutboxDispatchNotifier(executor, dispatcher);
    }

    /**
     * 快速通道关闭时的降级空通知器。
     */
    @Bean
    @ConditionalOnMissingBean(UserOutboxDispatchNotifier.class)
    public UserOutboxDispatchNotifier noopUserOutboxDispatchNotifier() {
        return new NoopUserOutboxDispatchNotifier();
    }
}
