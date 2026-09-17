package com.calles.platform.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 审核服务异步线程池配置 (AuditThreadPoolConfiguration)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务基础设施与运行时配置层；</li>
 *   <li><b>主要职责</b>：基于 Java 21 虚拟线程 (Virtual Threads) 为机审文本、封面图像识别与音视频抽帧等多阶段并发调度提供高吞吐执行器；</li>
 *   <li><b>高并发优势</b>：消除传统平台线程固定容量与缓冲队列限制，等待外部拉流与云端机审 I/O 时自动挂起让出载体线程，具备超高并发弹性。</li>
 * </ul>
 * </p>
 */
@Configuration
public class AuditThreadPoolConfiguration {

    /**
     * 机审引擎多阶段并发执行器（基于 Java 21 虚拟线程）。
     *
     * <p>特性说明：
     * <ul>
     *   <li>虚拟线程：启用 virtualThreads=true，每个审查子任务均在轻量级虚拟线程中并发执行；</li>
     *   <li>线程前缀：audit-engine-vt-，便于日志分析与链路诊断；</li>
     *   <li>非池化轻量设计：消除队列与容量上限瓶颈，无需拒绝策略。</li>
     * </ul>
     * </p>
     *
     * @return 基于虚拟线程的执行器 Bean
     */
    @Bean(name = "auditEngineExecutor")
    public Executor auditEngineExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("audit-engine-vt-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
