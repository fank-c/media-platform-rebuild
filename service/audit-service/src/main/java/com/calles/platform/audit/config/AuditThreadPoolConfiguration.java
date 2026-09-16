package com.calles.platform.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 审核服务异步线程池配置 (AuditThreadPoolConfiguration)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务基础设施与运行时配置层；</li>
 *   <li><b>主要职责</b>：为机审文本、封面图像识别与音视频抽帧等多阶段并发调度提供隔离线程池，避免耗时任务阻塞 Web 容器工作线程；</li>
 *   <li><b>资源防护</b>：通过核心池限制、队列缓冲与 CallerRunsPolicy 拒绝策略提供系统过载反压保护。</li>
 * </ul>
 * </p>
 */
@Configuration
public class AuditThreadPoolConfiguration {

    /**
     * 机审引擎多阶段并发执行器。
     *
     * <p>配置参数：
     * <ul>
     *   <li>核心线程数：8</li>
     *   <li>最大线程数：32</li>
     *   <li>缓冲队列深度：500</li>
     *   <li>线程前缀：audit-engine-</li>
     *   <li>拒绝策略：CallerRunsPolicy（过载时由提交线程同步处理，实现自然反压）</li>
     *   <li>优雅关机：等待任务完成，最长等待 30 秒</li>
     * </ul>
     * </p>
     *
     * @return 初始化的线程池执行器
     */
    @Bean(name = "auditEngineExecutor")
    public Executor auditEngineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-engine-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
