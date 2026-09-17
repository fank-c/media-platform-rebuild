package com.calles.platform.transcode.infrastructure.concurrency;

import com.calles.platform.transcode.config.TranscodeProperties;
import com.calles.platform.transcode.exception.TranscodeException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 宿主机硬件资源保护转码并发控制器 (TranscodeRateLimiter)。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>防雪崩保护</b>：基于公平信号量 (Fair Semaphore) 限制同时运行的高负载转码压制任务数，防止压垮宿主机 CPU/内存；</li>
 *   <li><b>排队超时防护</b>：当并发任务排队超时后主动抛出快速失败异常，触发消息退避与死信机制；</li>
 *   <li><b>资源指标监控</b>：提供可用许可数与排队积压观测指标。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscodeRateLimiter {

    private final TranscodeProperties properties;
    private Semaphore semaphore;

    @PostConstruct
    public void init() {
        int maxConcurrent = properties.getMaxConcurrentTasks() > 0 ? properties.getMaxConcurrentTasks() : 1;
        this.semaphore = new Semaphore(maxConcurrent, true);
        log.info("初始化转码并发保护限流器: maxConcurrentTasks={}", maxConcurrent);
    }

    /**
     * 在受控并发许可保护下执行转码任务动作。
     *
     * @param action 转码执行闭包
     * @param <T> 动作返回值类型
     * @return 动作执行结果
     * @throws TranscodeException 当获取许可超时或执行过程发生异常时抛出
     */
    public <T> T executeWithPermit(Callable<T> action) throws TranscodeException {
        // 等待获取许可的超时时限（默认使用任务最大超时的 2 倍，避免死锁无限挂起）
        long waitTimeoutSeconds = Math.max(properties.getTaskTimeoutSeconds() * 2L, 1200L);
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(waitTimeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.error("获取转码并发许可排队超时 (waitTimeout={}s), 当前可用许可: {}", waitTimeoutSeconds, semaphore.availablePermits());
                throw new TranscodeException("转码排队超时，系统负载饱和");
            }
            log.debug("成功获取转码执行许可，剩余可用许可: {}", semaphore.availablePermits());
            return action.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TranscodeException("等待转码并发许可时被线程中断", e);
        } catch (TranscodeException e) {
            throw e;
        } catch (Exception e) {
            throw new TranscodeException("转码动作执行失败: " + e.getMessage(), e);
        } finally {
            if (acquired) {
                semaphore.release();
                log.debug("已释放转码执行许可，当前可用许可: {}", semaphore.availablePermits());
            }
        }
    }

    /**
     * 获取当前空闲可用的并发配额数量。
     */
    public int getAvailablePermits() {
        return semaphore != null ? semaphore.availablePermits() : 0;
    }

    /**
     * 获取当前正在排队等待许可的线程估算数。
     */
    public int getQueueLength() {
        return semaphore != null ? semaphore.getQueueLength() : 0;
    }
}
