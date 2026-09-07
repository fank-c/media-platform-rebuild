package com.calles.platform.gateway.service;

import com.calles.platform.gateway.config.AuthProperties;
import com.calles.platform.gateway.dto.VerifyTokenResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 网关 Token 验证缓存。Redis 键只保存 Token 的 SHA-256 摘要，避免泄露完整 JWT。
 */
@Service
public class AuthCacheService {

    /** 缓存诊断日志记录器，不记录 Token 原文。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthCacheService.class);
    /** Redis 键前缀，隔离网关认证缓存与其他业务键。 */
    private static final String CACHE_PREFIX = "gateway:auth:";

    /** 仅使用字符串值的响应式 Redis 客户端。 */
    private final ReactiveStringRedisTemplate redisTemplate;
    /** 验证响应的 JSON 序列化器。 */
    private final ObjectMapper objectMapper;
    /** 缓存 TTL 与令牌寿命配置。 */
    private final AuthProperties authProperties;

    /**
     * 注入 Redis、JSON 序列化器和缓存策略。
     */
    public AuthCacheService(ReactiveStringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper,
                            AuthProperties authProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.authProperties = authProperties;
    }

    /**
     * 读取并解析缓存结果；缓存损坏按未命中处理，以便回源认证服务。
     */
    public Mono<VerifyTokenResponse> get(String token) {
        return redisTemplate.opsForValue().get(cacheKey(token))
                .flatMap(json -> {
                    try {
                        return Mono.just(objectMapper.readValue(json, VerifyTokenResponse.class));
                    } catch (JsonProcessingException ex) {
                        LOGGER.warn("解析 Token 验证缓存失败: {}", ex.getMessage());
                        return Mono.empty();
                    }
                });
    }

    /**
     * 写入验证结果，并将有效结果 TTL 限制在 Token 剩余寿命内。
     */
    public Mono<Void> put(String token, VerifyTokenResponse result) {
        try {
            String json = objectMapper.writeValueAsString(result);
            Duration ttl = authProperties.getCacheTtl();
            if (result.expiresAt() != null) {
                long remainingMillis = result.expiresAt() - System.currentTimeMillis();
                if (remainingMillis <= 0) {
                    return Mono.empty();
                }
                ttl = ttl.compareTo(Duration.ofMillis(remainingMillis)) < 0
                        ? ttl : Duration.ofMillis(remainingMillis);
            }
            return redisTemplate.opsForValue().set(cacheKey(token), json, ttl).then();
        } catch (JsonProcessingException ex) {
            LOGGER.warn("序列化 Token 验证缓存失败: {}", ex.getMessage());
            return Mono.empty();
        }
    }

    /**
     * 删除指定 Token 的缓存，用于注销后立即阻断旧 Token 的缓存命中路径。
     */
    public Mono<Void> evict(String token) {
        return redisTemplate.delete(cacheKey(token)).then();
    }

    /**
     * 使用 UTF-8 计算 Token 摘要，避免把敏感原文写入 Redis。
     */
    private String cacheKey(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return CACHE_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
