package com.calles.platform.auth.config;

import com.calles.platform.auth.application.outbox.AuthOutboxDispatchNotifier;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import com.calles.platform.auth.infrastructure.outbox.AfterCommitAuthOutboxDispatchNotifier;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxDispatcher;
import com.calles.platform.auth.infrastructure.outbox.NoopAuthOutboxDispatchNotifier;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 认证基础设施 Bean 配置。
 *
 * <p>除统一 UTC 时钟外，本配置还在启动期按开关创建有界的 Outbox 快速执行器；开关变更需要受控
 * 重启，不能假设配置热刷新会安全替换运行中的线程池。</p>
 */
@Configuration
public class AuthConfiguration {

    /** 启动期校验认证安全参数，避免无效配置进入令牌服务。 */
    private final AuthProperties properties;
    /** 启动期校验 Outbox 参数，避免调度器以不可恢复的时序运行。 */
    private final AuthOutboxProperties outboxProperties;
    /** 启动期校验资料补齐参数，避免误配置导致无界扫描。 */
    private final ProfileBackfillProperties profileBackfillProperties;

    /**
     * @param properties 已绑定的认证配置，不能在本配置类中提供密钥兜底值
     * @param outboxProperties Outbox 调度和快速提示配置
     * @param profileBackfillProperties 资料补齐调度配置
     */
    public AuthConfiguration(AuthProperties properties, AuthOutboxProperties outboxProperties,
            ProfileBackfillProperties profileBackfillProperties) {
        this.properties = properties;
        this.outboxProperties = outboxProperties;
        this.profileBackfillProperties = profileBackfillProperties;
    }

    /** 启动期统一校验所有配置，避免任务已启动后才暴露不可恢复的参数错误。 */
    @PostConstruct
    public void validateProperties() {
        // 在创建 JWT 算法前失败，禁止以空密钥或不合理 TTL 提供认证服务。
        properties.validate();
        outboxProperties.validate();
        profileBackfillProperties.validate();
    }

    /**
     * 统一使用 UTC，令牌过期和 Redis TTL 不受服务部署时区影响。
     *
     * @return 系统 UTC 时钟
     */
    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }

    /**
     * 仅在总发送和快速投递都开启时创建有限线程池。
     *
     * <p>明确使用 AbortPolicy，满队列时拒绝提示并交给扫描恢复，绝不在注册线程 CallerRuns。</p>
     *
     * @return 可在正常停机时有限等待的快速执行器
     */
    @Bean(name = "authOutboxFastDispatchExecutor")
    @ConditionalOnProperty(prefix = "auth.outbox", name = {"enabled", "fast-dispatch-enabled"}, havingValue = "true")
    public ThreadPoolTaskExecutor authOutboxFastDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(outboxProperties.getFastDispatchThreads());
        executor.setMaxPoolSize(outboxProperties.getFastDispatchThreads());
        executor.setQueueCapacity(outboxProperties.getFastDispatchQueueCapacity());
        executor.setThreadNamePrefix("auth-outbox-fast-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) outboxProperties.getShutdownAwait().toSeconds());
        return executor;
    }

    /**
     * 创建真实的提交后快速通知器；任务只携带 eventId。
     *
     * @param executor 有界快速执行器
     * @param dispatcher 统一按 ID 领取及发送入口
     * @param metrics 指标出口
     * @return 真实快速通知实现
     */
    @Bean
    @ConditionalOnProperty(prefix = "auth.outbox", name = {"enabled", "fast-dispatch-enabled"}, havingValue = "true")
    public AuthOutboxDispatchNotifier authOutboxDispatchNotifier(
            @Qualifier("authOutboxFastDispatchExecutor") ThreadPoolTaskExecutor executor,
            AuthOutboxDispatcher dispatcher, AuthOperationalMetrics metrics) {
        return new AfterCommitAuthOutboxDispatchNotifier(executor, dispatcher, metrics);
    }

    /**
     * 快速通道未启用时仍提供窄接口，避免注册因条件 Bean 缺失而无法启动。
     *
     * @param metrics 指标出口
     * @return 不执行任何发送的安全降级实现
     */
    @Bean
    @ConditionalOnMissingBean(AuthOutboxDispatchNotifier.class)
    public AuthOutboxDispatchNotifier noopAuthOutboxDispatchNotifier(AuthOperationalMetrics metrics) {
        return new NoopAuthOutboxDispatchNotifier(metrics);
    }
}
