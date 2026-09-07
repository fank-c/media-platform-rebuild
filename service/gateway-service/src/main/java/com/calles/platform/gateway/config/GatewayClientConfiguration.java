package com.calles.platform.gateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 网关内部 WebClient 配置，使认证服务的 {@code lb://} 地址通过服务发现解析。
 */
@Configuration
public class GatewayClientConfiguration {

    /**
     * 创建带负载均衡能力的客户端构造器，供 AuthServiceClient 调用服务发现地址。
     */
    @Bean
    @Primary
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}
