package com.calles.platform.interaction.exception;

import java.time.Duration;
import lombok.Getter;

/**
 * 分布式锁获取超时或等待放弃专用异常。
 *
 * <p>职责边界：当在指定等待时间内未能成功抢占分布式锁或本地锁时抛出此异常。
 * 业务层可精确捕获此异常进行平滑降级（如心跳只读查询返回当前断点），
 * 严禁与通用业务异常或系统运行时异常混淆，杜绝业务 Bug 被意外掩盖。</p>
 */
@Getter
public class LockAcquireTimeoutException extends RuntimeException {

    /** 尝试加锁的业务标识键。 */
    private final String lockKey;

    /** 配置的最大等待时间。 */
    private final Duration waitTime;

    /**
     * 构造锁获取超时异常。
     *
     * @param lockKey 锁业务标识键
     * @param waitTime 最大等待时间
     */
    public LockAcquireTimeoutException(String lockKey, Duration waitTime) {
        super(String.format("获取分布式锁超时或排队放弃: lockKey=%s, waitTime=%s", lockKey, waitTime));
        this.lockKey = lockKey;
        this.waitTime = waitTime;
    }

    /**
     * 构造带原因的锁获取超时或失败异常。
     *
     * @param lockKey 锁业务标识键
     * @param waitTime 最大等待时间
     * @param cause 底层异常根因
     */
    public LockAcquireTimeoutException(String lockKey, Duration waitTime, Throwable cause) {
        super(String.format("获取分布式锁失败: lockKey=%s, waitTime=%s", lockKey, waitTime), cause);
        this.lockKey = lockKey;
        this.waitTime = waitTime;
    }
}
