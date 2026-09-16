package com.calles.platform.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 网关认证拦截配置，集中管理认证服务地址、缓存生命周期和匿名白名单。
 */
@Component
@ConfigurationProperties(prefix = "gateway.auth")
public class AuthProperties {

    /** 认证服务的服务发现地址；生产环境通过配置覆盖。 */
    private String authServiceUrl = "lb://auth-service";

    /** 验证结果缓存时长，不应超过访问令牌寿命。 */
    private Duration cacheTtl = Duration.ofMinutes(10);

    /** 无需访问令牌的公开端点。 */
    private List<String> whitelist = List.of(
            "/api/auth/ping",
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/files/assets/**",
            "/api/audit/callback/**",
            "/actuator/health",
            "/actuator/info");

    public String getAuthServiceUrl() {
        return authServiceUrl;
    }

    /**
     * 设置认证服务地址。
     */
    public void setAuthServiceUrl(String authServiceUrl) {
        this.authServiceUrl = authServiceUrl;
    }

    /**
     * 获取验证缓存 TTL。
     */
    public Duration getCacheTtl() {
        return cacheTtl;
    }

    /**
     * 设置验证缓存 TTL。
     */
    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    /**
     * 获取匿名白名单路径。
     */
    public List<String> getWhitelist() {
        return whitelist;
    }

    /**
     * 设置匿名白名单路径。
     */
    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }
}
