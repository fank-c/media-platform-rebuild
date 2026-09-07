package com.calles.platform.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证 Outbox 投递与恢复的运行参数。
 *
 * <p>这些配置只影响后台发送、重试、租约和内存唤醒；注册事务无论开关状态都会继续原子写入
 * {@code auth_outbox}。快速开关及线程池仅在启动期读取，修改后需受控重启。</p>
 */
@ConfigurationProperties(prefix = "auth.outbox")
public class AuthOutboxProperties {

    /** 是否执行后台发送；关闭后扫描和快速投递都不启动，事件仍持续落库。 */
    private boolean enabled = true;
    /** 是否启用提交后的内存快速提示；关闭时仅由扫描恢复。 */
    private boolean fastDispatchEnabled;
    /** 快速任务固定工作线程数，限制 Broker 阻塞时占用的资源。 */
    private int fastDispatchThreads = 2;
    /** 快速任务有界队列容量，满时显式拒绝并回退到扫描。 */
    private int fastDispatchQueueCapacity = 256;
    /** 单次扫描发现的候选 ID 上限，不代表同时持有的租约数量。 */
    private int batchSize = 100;
    /** 单条消息允许的最大领取次数，达到上限后由扫描收敛为 FAILED。 */
    private int maxAttempts = 20;
    /** 等待 RabbitMQ Publisher Confirm 的最长时间；不覆盖发送或数据库操作耗时。 */
    private Duration confirmTimeout = Duration.ofSeconds(5);
    /** 发布实例持有领取权的最长时间，必须长于确认等待时间。 */
    private Duration lease = Duration.ofSeconds(30);
    /** 两轮扫描完成之间的固定延迟，快速提示丢失时以此频率恢复。 */
    private Duration pollInterval = Duration.ofSeconds(1);
    /** 刷新全表积压聚合的独立周期，不参与单条发送判定。 */
    private Duration backlogRefreshInterval = Duration.ofSeconds(60);
    /** 正常停机最多等待在途快速任务完成的时间。 */
    private Duration shutdownAwait = Duration.ofSeconds(10);

    /** @return 是否启用总发送通道 */
    public boolean isEnabled() { return enabled; }
    /** @param enabled 是否启用总发送通道 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** @return 是否启用提交后快速提示 */
    public boolean isFastDispatchEnabled() { return fastDispatchEnabled; }
    /** @param fastDispatchEnabled 是否启用提交后快速提示 */
    public void setFastDispatchEnabled(boolean fastDispatchEnabled) { this.fastDispatchEnabled = fastDispatchEnabled; }
    /** @return 快速任务固定线程数 */
    public int getFastDispatchThreads() { return fastDispatchThreads; }
    /** @param fastDispatchThreads 快速任务固定线程数 */
    public void setFastDispatchThreads(int fastDispatchThreads) { this.fastDispatchThreads = fastDispatchThreads; }
    /** @return 快速任务有界队列容量 */
    public int getFastDispatchQueueCapacity() { return fastDispatchQueueCapacity; }
    /** @param fastDispatchQueueCapacity 快速任务有界队列容量 */
    public void setFastDispatchQueueCapacity(int fastDispatchQueueCapacity) {
        this.fastDispatchQueueCapacity = fastDispatchQueueCapacity;
    }
    /** @return 扫描候选上限 */
    public int getBatchSize() { return batchSize; }
    /** @param batchSize 扫描候选上限 */
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    /** @return 自动领取次数上限 */
    public int getMaxAttempts() { return maxAttempts; }
    /** @param maxAttempts 自动领取次数上限 */
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    /** @return Publisher Confirm 等待时长 */
    public Duration getConfirmTimeout() { return confirmTimeout; }
    /** @param confirmTimeout Publisher Confirm 等待时长 */
    public void setConfirmTimeout(Duration confirmTimeout) { this.confirmTimeout = confirmTimeout; }
    /** @return 消息领取租约 */
    public Duration getLease() { return lease; }
    /** @param lease 消息领取租约 */
    public void setLease(Duration lease) { this.lease = lease; }
    /** @return 扫描固定延迟 */
    public Duration getPollInterval() { return pollInterval; }
    /** @param pollInterval 扫描固定延迟 */
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    /** @return 积压快照刷新周期 */
    public Duration getBacklogRefreshInterval() { return backlogRefreshInterval; }
    /** @param backlogRefreshInterval 积压快照刷新周期 */
    public void setBacklogRefreshInterval(Duration backlogRefreshInterval) {
        this.backlogRefreshInterval = backlogRefreshInterval;
    }
    /** @return 正常停机等待时长 */
    public Duration getShutdownAwait() { return shutdownAwait; }
    /** @param shutdownAwait 正常停机等待时长 */
    public void setShutdownAwait(Duration shutdownAwait) { this.shutdownAwait = shutdownAwait; }

    /**
     * 启动期校验有界资源、重试及最低租约时序约束。
     *
     * <p>{@code lease > confirmTimeout} 只是不低于确认等待的基础条件，不能证明完整发送流程不会超过
     * 租约；实际余量仍需在隔离环境测量。</p>
     */
    public void validate() {
        if (fastDispatchThreads < 1 || fastDispatchThreads > 16) {
            throw new IllegalStateException("Auth Outbox fastDispatchThreads must be between 1 and 16");
        }
        if (fastDispatchQueueCapacity < 1 || fastDispatchQueueCapacity > 10_000) {
            throw new IllegalStateException("Auth Outbox fastDispatchQueueCapacity must be between 1 and 10000");
        }
        if (batchSize < 1 || batchSize > 1_000 || maxAttempts < 1) {
            throw new IllegalStateException("Auth Outbox batchSize must be 1..1000 and maxAttempts must be positive");
        }
        if (isZeroOrNegative(confirmTimeout) || isZeroOrNegative(lease) || isZeroOrNegative(pollInterval)
                || isZeroOrNegative(backlogRefreshInterval) || isZeroOrNegative(shutdownAwait)) {
            throw new IllegalStateException("Auth Outbox durations must be positive");
        }
        if (lease.compareTo(confirmTimeout) <= 0) {
            throw new IllegalStateException("Auth Outbox lease must be greater than confirmTimeout");
        }
        if (shutdownAwait.compareTo(Duration.ofSeconds(1)) < 0
                || shutdownAwait.compareTo(Duration.ofSeconds(Integer.MAX_VALUE)) > 0) {
            throw new IllegalStateException("Auth Outbox shutdownAwait must be between 1 second and Integer.MAX_VALUE seconds");
        }
    }

    /** 判断可绑定的时长是否为空、零或负数。 */
    private boolean isZeroOrNegative(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }
}
