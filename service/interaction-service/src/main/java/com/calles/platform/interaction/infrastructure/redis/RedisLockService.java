package com.calles.platform.interaction.infrastructure.redis;

import com.calles.platform.interaction.exception.LockAcquireTimeoutException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 通用分布式锁服务组件。
 *
 * <p>职责边界与协作对象：
 * <ul>
 *   <li>基于 Redisson 实现分布式排他锁，默认激活<b>看门狗（Watchdog）自动续期机制</b>，彻底杜绝业务未执行完而锁提前失效的隐患；</li>
 *   <li>高可用本地降级：在 Redisson 未配置、Redis 宕机或网络不可达等异常场景下，平滑降级至本地 JVM 细粒度 {@link ReentrantLock}；</li>
 *   <li>精准异常与排他保障：在 Redis 服务正常但锁被其他实例持有时，等待超时精准抛出 {@link LockAcquireTimeoutException}，严禁降级为本地锁穿透分布式互斥；</li>
 *   <li>异常透明透传：受锁保护的业务任务内部抛出的业务与系统异常原样向外透传，绝不被锁组件吞噬。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class RedisLockService {

    /** Redisson 客户端实例，若未启用或未配置则为 null。 */
    private final RedissonClient redissonClient;

    /** 本地锁降级容器：当 Redis 异常或离线时，基于键级别细粒度 ReentrantLock 兜底。 */
    private final ConcurrentHashMap<String, ReentrantLock> localLockMap = new ConcurrentHashMap<>();

    /**
     * 构造分布式锁服务组件。
     *
     * @param redissonClient Redisson 客户端（非强依赖，允许为 null 以支持无 Redis 本地降级运行）
     */
    public RedisLockService(@Autowired(required = false) RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 在分布式锁保护下执行带返回值的业务任务（默认采用 Redisson 看门狗自动续期）。
     *
     * @param lockKey 锁业务标识键 (如 int:lock:watch:user01:vid100)
     * @param waitTime 最大尝试获取锁等待时间
     * @param task 受锁保护执行的业务逻辑
     * @param <T> 返回值泛型
     * @return 业务执行结果
     * @throws LockAcquireTimeoutException 若在 waitTime 内未能获取到锁
     */
    public <T> T executeWithLock(String lockKey, Duration waitTime, Supplier<T> task) {
        return executeWithLock(lockKey, waitTime, null, task);
    }

    /**
     * 在分布式锁保护下执行带返回值的业务任务。
     *
     * @param lockKey 锁业务标识键
     * @param waitTime 最大尝试获取锁等待时间
     * @param leaseTime 锁显式租约时长（若为 null 或小于等于 0，则自动启用 Redisson 看门狗自动续期）
     * @param task 受锁保护执行的业务逻辑
     * @param <T> 返回值泛型
     * @return 业务执行结果
     * @throws LockAcquireTimeoutException 若在 waitTime 内未能获取到锁
     */
    public <T> T executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> task) {
        if (lockKey == null || lockKey.isBlank()) {
            throw new IllegalArgumentException("锁标识键 lockKey 不能为空");
        }
        if (task == null) {
            throw new IllegalArgumentException("待执行业务任务 task 不能为空");
        }

        RLock rLock = null;
        boolean isRedissonLockAcquired = false;
        boolean isLocalLockAcquired = false;
        boolean redisUnavailable = false;

        long waitMillis = waitTime != null ? Math.max(0, waitTime.toMillis()) : 0L;

        // 步骤 1: 优先尝试通过 Redisson 获取分布式锁
        if (redissonClient != null) {
            try {
                rLock = redissonClient.getLock(lockKey);
                if (leaseTime != null && !leaseTime.isZero() && !leaseTime.isNegative()) {
                    // 显式指定租约时长加锁（不走看门狗）
                    isRedissonLockAcquired = rLock.tryLock(waitMillis, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
                } else {
                    // 未指定租约时长：激活 Redisson 内部看门狗机制（默认 30 秒超时，每 10 秒自动续期）
                    isRedissonLockAcquired = rLock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                // 步骤 1.1: 线程等待被中断，恢复中断位并向外抛出锁超时异常
                Thread.currentThread().interrupt();
                throw new LockAcquireTimeoutException(lockKey, waitTime, e);
            } catch (Exception e) {
                // 步骤 1.2: 捕获 Redis 网络异常、宕机等服务不可用异常，标记平滑降级
                log.warn("Redisson 分布式锁异常，准备平滑降级至本地锁兜底: key={}, error={}", lockKey, e.getMessage());
                redisUnavailable = true;
            }
        }

        // 步骤 2: 降级决策
        // 关键安全防线：若 Redis 服务正常，仅因超时未抢到锁（isRedissonLockAcquired == false），严禁降级到本地锁穿透分布式互斥！
        // 只有当 Redisson 未配置 或 Redis 产生服务不可用异常时，才允许降级为本地 JVM 细粒度锁
        if (redissonClient == null || redisUnavailable) {
            log.info("启用本地 JVM 细粒度锁兜底: key={}", lockKey);
            isLocalLockAcquired = acquireLocalLock(lockKey, waitTime);
        }

        // 步骤 3: 锁获取失败判定
        if (!isRedissonLockAcquired && !isLocalLockAcquired) {
            throw new LockAcquireTimeoutException(lockKey, waitTime);
        }

        try {
            // 步骤 4: 在锁保护的关键区内执行业务逻辑（业务异常原样向上透传，绝不拦截）
            return task.get();
        } finally {
            // 步骤 5: 安全释放锁资源
            release(lockKey, rLock, isRedissonLockAcquired, isLocalLockAcquired);
        }
    }

    /**
     * 在分布式锁保护下执行无返回值的任务。
     *
     * @param lockKey 锁业务标识键
     * @param waitTime 最大等待时间
     * @param runnable 待执行任务
     */
    public void executeWithLock(String lockKey, Duration waitTime, Runnable runnable) {
        executeWithLock(lockKey, waitTime, null, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 在分布式锁保护下执行无返回值的任务（兼容旧入参格式）。
     *
     * @param lockKey 锁业务标识键
     * @param waitTime 最大等待时间
     * @param leaseTime 租约时长
     * @param runnable 待执行任务
     */
    public void executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Runnable runnable) {
        executeWithLock(lockKey, waitTime, leaseTime, () -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 本地锁降级获取。
     *
     * @param lockKey 锁标识
     * @param waitTime 等待时长
     * @return true 若成功获取本地锁，false 若超时或被中断
     */
    private boolean acquireLocalLock(String lockKey, Duration waitTime) {
        ReentrantLock lock = localLockMap.computeIfAbsent(lockKey, k -> new ReentrantLock());
        try {
            long waitMillis = waitTime != null ? Math.max(0, waitTime.toMillis()) : 0L;
            return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 安全释放锁资源。
     *
     * @param lockKey 锁业务键
     * @param rLock Redisson 锁对象
     * @param isRedissonLockAcquired 是否持有 Redisson 锁
     * @param isLocalLockAcquired 是否持有本地锁
     */
    private void release(String lockKey, RLock rLock, boolean isRedissonLockAcquired, boolean isLocalLockAcquired) {
        // 步骤 1: 释放 Redisson 分布式锁
        if (isRedissonLockAcquired && rLock != null) {
            try {
                if (rLock.isHeldByCurrentThread()) {
                    rLock.unlock();
                }
            } catch (Exception e) {
                log.debug("Redisson 释放锁异常 (可能由于连接中断或锁已被清理): key={}, error={}", lockKey, e.getMessage());
            }
        }

        // 步骤 2: 释放本地降级锁
        if (isLocalLockAcquired) {
            ReentrantLock localLock = localLockMap.get(lockKey);
            if (localLock != null && localLock.isHeldByCurrentThread()) {
                localLock.unlock();
            }
        }
    }
}
