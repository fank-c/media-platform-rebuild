package com.calles.platform.user.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * 用户服务运行参数校验配置。
 *
 * <p>只负责确保已绑定参数符合消费者和资料展示约束，不承载 RabbitMQ、HTTP 或数据库业务流程。</p>
 */
@Configuration
public class UserRuntimeConfiguration {

    /** 账号创建事件消费者的运行参数。 */
    private final UserMessagingProperties messagingProperties;
    /** 用户资料展示运行参数。 */
    private final UserProfileProperties profileProperties;

    /**
     * @param messagingProperties 账号创建事件消费运行参数
     * @param profileProperties 用户资料展示运行参数
     */
    public UserRuntimeConfiguration(UserMessagingProperties messagingProperties,
            UserProfileProperties profileProperties) {
        this.messagingProperties = messagingProperties;
        this.profileProperties = profileProperties;
    }

    /** 启动期校验消息与资料配置，禁止监听器以无效并发或重试次数运行。 */
    @PostConstruct
    public void validateProperties() {
        messagingProperties.validate();
        profileProperties.validate();
    }
}
