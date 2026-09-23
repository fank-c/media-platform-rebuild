package com.calles.platform.user.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户微服务事务性发件箱 (Outbox) 运行参数配置类。
 */
@ConfigurationProperties(prefix = "user.outbox")
public class UserOutboxProperties {

    /** 是否启用发件箱投递总开关；关闭后快速投递与定时补偿扫描均暂停。 */
    private boolean enabled = true;

    /** 是否启用事务提交后的毫秒级内存快速投递。 */
    private boolean fastDispatchEnabled = true;

    /** 单次扫描发现的候选 ID 上限。 */
    private int batchSize = 100;

    /** 单条消息允许的最大尝试投递次数。 */
    private int maxAttempts = 20;

    /** 等待 RabbitMQ Broker Publisher Confirm ACK 的超时时间。 */
    private Duration confirmTimeout = Duration.ofSeconds(5);

    /** 分布式认领租约长度，必须大于确认超时时间。 */
    private Duration lease = Duration.ofSeconds(30);

    /** 定时补偿自愈扫描固定延迟。 */
    private Duration pollInterval = Duration.ofSeconds(5);

    /** 正常优雅停机最多等待在途快速任务完成的时长。 */
    private Duration shutdownAwait = Duration.ofSeconds(10);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFastDispatchEnabled() {
        return fastDispatchEnabled;
    }

    public void setFastDispatchEnabled(boolean fastDispatchEnabled) {
        this.fastDispatchEnabled = fastDispatchEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }

    public Duration getLease() {
        return lease;
    }

    public void setLease(Duration lease) {
        this.lease = lease;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getShutdownAwait() {
        return shutdownAwait;
    }

    public void setShutdownAwait(Duration shutdownAwait) {
        this.shutdownAwait = shutdownAwait;
    }

    /**
     * 启动期配置合法性校验。
     */
    public void validate() {
        if (batchSize < 1 || batchSize > 1_000 || maxAttempts < 1) {
            throw new IllegalStateException("User Outbox batchSize 必须在 1..1000 之间且 maxAttempts 必须大于 0");
        }
        if (isZeroOrNegative(confirmTimeout) || isZeroOrNegative(lease)
                || isZeroOrNegative(pollInterval) || isZeroOrNegative(shutdownAwait)) {
            throw new IllegalStateException("User Outbox 相关时长配置必须为大于 0 的正数");
        }
        if (lease.compareTo(confirmTimeout) <= 0) {
            throw new IllegalStateException("User Outbox 租约时长 lease 必须严格大于确认超时时长 confirmTimeout");
        }
    }

    private boolean isZeroOrNegative(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }
}
