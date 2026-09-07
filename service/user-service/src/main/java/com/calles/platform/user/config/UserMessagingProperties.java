package com.calles.platform.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户服务账号创建事件消费者的运行参数。
 *
 * <p>该配置只影响消费是否启用、并发度和有限重试；不会修改账号创建事件的契约或幂等语义。</p>
 */
@ConfigurationProperties(prefix = "user.messaging.account-created")
public class UserMessagingProperties {

    /** 是否创建 RabbitMQ 消费适配器，默认开启。 */
    private boolean enabled = true;
    /** 并发消费者数量，必须为正数。 */
    private int concurrency = 1;
    /** 单条消息的最大消费尝试次数，包含首次处理，必须为正数。 */
    private int maxAttempts = 3;

    /** @return 是否启用账号创建事件消费者 */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled 是否启用账号创建事件消费者 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return 消费者并发数量 */
    public int getConcurrency() {
        return concurrency;
    }

    /** @param concurrency 消费者并发数量 */
    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    /** @return 单条消息最大处理尝试次数 */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /** @param maxAttempts 单条消息最大处理尝试次数 */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** 启动期校验并发度和有限重试上限。 */
    public void validate() {
        if (concurrency < 1 || maxAttempts < 1) {
            throw new IllegalStateException("User account-created concurrency and maxAttempts must be positive");
        }
    }
}
