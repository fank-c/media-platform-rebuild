package com.calles.platform.content.config;

import com.calles.platform.content.application.outbox.ContentOutboxDispatchNotifier;
import com.calles.platform.content.infrastructure.outbox.dispatch.ContentOutboxDispatcher;
import com.calles.platform.content.infrastructure.outbox.notify.AfterCommitContentOutboxDispatchNotifier;
import com.calles.platform.content.infrastructure.outbox.notify.NoopContentOutboxDispatchNotifier;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Content Outbox 发件箱核心基础设施 Bean 配置类。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>配置校验</b>：服务启动时主动校验 Outbox 运行参数合法性；</li>
 *   <li><b>虚拟线程支持</b>：配置基于 Java 21 虚拟线程的快速派发执行器；</li>
 *   <li><b>按需组装</b>：根据配置动态提供真正的 {@link AfterCommitContentOutboxDispatchNotifier} 或降级的 {@link NoopContentOutboxDispatchNotifier}。</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableConfigurationProperties(ContentOutboxProperties.class)
public class ContentOutboxConfiguration {

    private final ContentOutboxProperties outboxProperties;

    public ContentOutboxConfiguration(ContentOutboxProperties outboxProperties) {
        this.outboxProperties = outboxProperties;
    }

    /**
     * 容器启动自检：校验 Outbox 各项超时与重试参数合法性。
     */
    @PostConstruct
    public void validateProperties() {
        outboxProperties.validate();
    }

    /**
     * 统一 UTC 时钟 Bean。
     *
     * @return UTC 系统时钟
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock contentClock() {
        return Clock.systemUTC();
    }

    /**
     * 基于 Java 21 虚拟线程的提交后 Outbox 快速发送执行器。
     *
     * @return 快速异步执行器 TaskExecutor
     */
    @Bean(name = "contentOutboxFastDispatchExecutor")
    @ConditionalOnProperty(prefix = "content.outbox", name = {"enabled", "fast-dispatch-enabled"}, havingValue = "true", matchIfMissing = true)
    public TaskExecutor contentOutboxFastDispatchExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("content-outbox-fast-vt-");
        executor.setVirtualThreads(true);
        return executor;
    }

    /**
     * 真实事务提交后快速分发通知器。
     *
     * @param executor 虚拟线程执行器
     * @param dispatcher 统一任务分发调度器
     * @return 真实快速通知器
     */
    @Bean
    @ConditionalOnProperty(prefix = "content.outbox", name = {"enabled", "fast-dispatch-enabled"}, havingValue = "true", matchIfMissing = true)
    public ContentOutboxDispatchNotifier contentOutboxDispatchNotifier(
            @Qualifier("contentOutboxFastDispatchExecutor") TaskExecutor executor,
            ContentOutboxDispatcher dispatcher) {
        return new AfterCommitContentOutboxDispatchNotifier(executor, dispatcher);
    }

    /**
     * 快速通道关闭时的降级通知器。
     *
     * @return 空操作通知器
     */
    @Bean
    @ConditionalOnMissingBean(ContentOutboxDispatchNotifier.class)
    public ContentOutboxDispatchNotifier noopContentOutboxDispatchNotifier() {
        return new NoopContentOutboxDispatchNotifier();
    }
}
