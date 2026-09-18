package com.calles.platform.content.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内容微服务事务性发件箱 (Outbox) 运行参数配置类。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：绑定 {@code content.outbox.*} 配置项；</li>
 *   <li><b>安全自检</b>：通过 {@link #validate()} 在启动期实施有界范围与有效性约束校验；</li>
 *   <li><b>隔离性</b>：仅约束发件箱异步投递与补偿扫描，不影响业务主流程向 {@code content_outbox} 表原子落库。</li>
 * </ul>
 * </p>
 */
@ConfigurationProperties(prefix = "content.outbox")
public class ContentOutboxProperties {

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
    private Duration pollInterval = Duration.ofSeconds(1);

    /** 正常优雅停机最多等待在途快速任务完成的时长。 */
    private Duration shutdownAwait = Duration.ofSeconds(10);

    /**
     * 获取是否启用总通道。
     *
     * @return true 启用，false 禁用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用总通道。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取是否启用快速投递。
     *
     * @return true 启用，false 禁用
     */
    public boolean isFastDispatchEnabled() {
        return fastDispatchEnabled;
    }

    /**
     * 设置是否启用快速投递。
     *
     * @param fastDispatchEnabled 是否启用
     */
    public void setFastDispatchEnabled(boolean fastDispatchEnabled) {
        this.fastDispatchEnabled = fastDispatchEnabled;
    }

    /**
     * 获取单批次扫描上限。
     *
     * @return 单批次数量
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * 设置单批次扫描上限。
     *
     * @param batchSize 单批次数量
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * 获取最大重试次数。
     *
     * @return 最大重试次数
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * 设置最大重试次数。
     *
     * @param maxAttempts 最大重试次数
     */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /**
     * 获取确认超时时间。
     *
     * @return 确认超时 Duration
     */
    public Duration getConfirmTimeout() {
        return confirmTimeout;
    }

    /**
     * 设置确认超时时间。
     *
     * @param confirmTimeout 确认超时 Duration
     */
    public void setConfirmTimeout(Duration confirmTimeout) {
        this.confirmTimeout = confirmTimeout;
    }

    /**
     * 获取租约保护期长度。
     *
     * @return 租约 Duration
     */
    public Duration getLease() {
        return lease;
    }

    /**
     * 设置租约保护期长度。
     *
     * @param lease 租约 Duration
     */
    public void setLease(Duration lease) {
        this.lease = lease;
    }

    /**
     * 获取补偿扫描轮询间隔。
     *
     * @return 轮询间隔 Duration
     */
    public Duration getPollInterval() {
        return pollInterval;
    }

    /**
     * 设置补偿扫描轮询间隔。
     *
     * @param pollInterval 轮询间隔 Duration
     */
    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    /**
     * 获取优雅停机等待时长。
     *
     * @return 停机等待 Duration
     */
    public Duration getShutdownAwait() {
        return shutdownAwait;
    }

    /**
     * 设置优雅停机等待时长。
     *
     * @param shutdownAwait 停机等待 Duration
     */
    public void setShutdownAwait(Duration shutdownAwait) {
        this.shutdownAwait = shutdownAwait;
    }

    /**
     * 启动期配置合法性校验。
     *
     * <p>确保 batchSize、maxAttempts 必须为正数，时长必须大于 0，且 lease 必须严格大于 confirmTimeout。</p>
     */
    public void validate() {
        if (batchSize < 1 || batchSize > 1_000 || maxAttempts < 1) {
            throw new IllegalStateException("Content Outbox batchSize 必须在 1..1000 之间且 maxAttempts 必须大于 0");
        }
        if (isZeroOrNegative(confirmTimeout) || isZeroOrNegative(lease)
                || isZeroOrNegative(pollInterval) || isZeroOrNegative(shutdownAwait)) {
            throw new IllegalStateException("Content Outbox 相关时长配置必须为大于 0 的正数");
        }
        if (lease.compareTo(confirmTimeout) <= 0) {
            throw new IllegalStateException("Content Outbox 租约时长 lease 必须严格大于确认超时时长 confirmTimeout");
        }
    }

    /**
     * 判断指定时长是否为 null、0 或负数。
     *
     * @param duration 待检查的时长对象
     * @return true 若为空或非正数
     */
    private boolean isZeroOrNegative(Duration duration) {
        return duration == null || duration.isZero() || duration.isNegative();
    }
}
