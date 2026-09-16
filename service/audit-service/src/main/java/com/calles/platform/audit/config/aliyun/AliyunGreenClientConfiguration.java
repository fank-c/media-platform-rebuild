package com.calles.platform.audit.config.aliyun;

import com.aliyun.green20220302.Client;
import com.aliyun.teaopenapi.models.Config;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云内容安全 2.0 (Aliyun Green 2022-03-02) 客户端基础设施装配配置。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核服务基础设施配置装配层，负责初始化阿里云 SDK Client 单例；</li>
 *   <li><b>条件装配</b>：仅当 {@code audit.aliyun.enabled=true} 时初始化客户端；默认关闭时完全不创建外部网络连接；</li>
 *   <li><b>协作对象</b>：读取 {@link AliyunGreenProperties}，向 Spring 容器注入 {@link Client} 供阿里云图像与视频引擎消费。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AliyunGreenProperties.class)
public class AliyunGreenClientConfiguration {

    /**
     * 构建阿里云内容安全 2.0 Client 单例 Bean。
     *
     * @param properties 阿里云内容安全配置属性
     * @return 初始化的阿里云 Client 实例
     * @throws Exception 若初始化通信客户端失败
     */
    @Bean
    @ConditionalOnProperty(name = "audit.aliyun.enabled", havingValue = "true")
    public Client aliyunGreenClient(AliyunGreenProperties properties) throws Exception {
        log.info("初始化阿里云内容安全 2.0 Client: endpoint=[{}]", properties.getEndpoint());

        // 步骤 1：构建 OpenAPI 基础连接与鉴权配置
        Config config = new Config()
                .setAccessKeyId(properties.getAccessKeyId())
                .setAccessKeySecret(properties.getAccessKeySecret())
                .setEndpoint(properties.getEndpoint());

        // 步骤 2：创建并返回线程安全的 Client 客户端单例
        return new Client(config);
    }
}
