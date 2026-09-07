package com.calles.platform.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证服务安全配置。JWT 密钥没有代码默认值，必须由运行环境或受保护的 Nacos 配置注入。
 */
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /** HMAC 签名密钥，只允许由受保护的运行环境注入。 */
    private String jwtSecret;
    /** JWT 签发方，用于限制接受本服务签发的令牌。 */
    private String jwtIssuer = "auth-service";
    /** JWT 受众，防止令牌被其他系统当作本平台凭据使用。 */
    private String jwtAudience = "media-platform";
    /** Access Token 的短期有效时间，单位为秒。 */
    private long accessTokenTtlSeconds = 900;
    /** Refresh Token 对应 Redis 会话的有效时间，单位为秒。 */
    private long refreshTokenTtlSeconds = 2_592_000;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getJwtIssuer() {
        return jwtIssuer;
    }

    public void setJwtIssuer(String jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
    }

    public String getJwtAudience() {
        return jwtAudience;
    }

    public void setJwtAudience(String jwtAudience) {
        this.jwtAudience = jwtAudience;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    public Duration accessTokenTtl() {
        // 由秒数属性统一换算，调用方不应自行解释配置单位。
        return Duration.ofSeconds(accessTokenTtlSeconds);
    }

    public Duration refreshTokenTtl() {
        // Refresh Token 与 Redis 会话共用 TTL，确保过期后凭据无法继续换发。
        return Duration.ofSeconds(refreshTokenTtlSeconds);
    }

    /**
     * 在创建签名算法前校验关键配置，避免空密钥或无效 TTL 让服务以不安全状态运行。
     */
    public void validate() {
        if (jwtSecret == null || jwtSecret.isBlank()
                || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("AUTH_JWT_SECRET must contain at least 32 bytes");
        }
        if (jwtIssuer == null || jwtIssuer.isBlank() || jwtAudience == null || jwtAudience.isBlank()) {
            throw new IllegalStateException("JWT issuer and audience must not be blank");
        }
        if (accessTokenTtlSeconds <= 0 || refreshTokenTtlSeconds <= 0
                || refreshTokenTtlSeconds <= accessTokenTtlSeconds) {
            throw new IllegalStateException("JWT TTL values are invalid");
        }
    }
}
