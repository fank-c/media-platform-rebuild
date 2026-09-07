package com.calles.platform.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 历史认证账号资料初始化事件补齐的运行参数。
 *
 * <p>默认关闭且 dry-run，避免在共享环境中未经确认写入补齐进度和 Outbox。</p>
 */
@ConfigurationProperties(prefix = "auth.profile-backfill")
public class ProfileBackfillProperties {

    /** 是否执行周期性补齐扫描，默认关闭。 */
    private boolean enabled;
    /** 是否仅统计候选而不写入进度和 Outbox，默认开启。 */
    private boolean dryRun = true;
    /** 单次扫描账号数量上限，必须为正数。 */
    private int batchSize = 100;
    /** 两轮扫描之间的固定延迟。 */
    private Duration pollInterval = Duration.ofSeconds(10);

    /** @return 是否启用补齐调度 */
    public boolean isEnabled() { return enabled; }
    /** @param enabled 是否启用补齐调度 */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** @return 是否仅统计候选 */
    public boolean isDryRun() { return dryRun; }
    /** @param dryRun 是否仅统计候选 */
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    /** @return 单批扫描上限 */
    public int getBatchSize() { return batchSize; }
    /** @param batchSize 单批扫描上限 */
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    /** @return 补齐轮询固定延迟 */
    public Duration getPollInterval() { return pollInterval; }
    /** @param pollInterval 补齐轮询固定延迟 */
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }

    /** 启动期校验扫描批量和调度间隔，避免空转或无界读取。 */
    public void validate() {
        if (batchSize < 1) {
            throw new IllegalStateException("Profile backfill batchSize must be positive");
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalStateException("Profile backfill pollInterval must be positive");
        }
    }
}
