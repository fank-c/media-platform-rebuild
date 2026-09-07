package com.calles.platform.gateway.filter;

import com.calles.platform.gateway.config.AuthProperties;
import com.calles.platform.gateway.dto.VerifyTokenResponse;
import com.calles.platform.gateway.service.AuthCacheService;
import com.calles.platform.gateway.service.AuthServiceClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关全局认证拦截器，负责白名单判断、Token 验证、身份头注入和失败响应。
 *
 * <p>身份头先被移除再写入，防止客户端伪造 Header；白名单请求也会清理这些头，避免公开端点
 * 将伪造身份传递给下游。</p>
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** 认证过滤日志记录器，不输出完整 Token。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthGlobalFilter.class);
    /** 标准 Authorization Bearer 前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";
    /** 客户端不可直接控制的网关身份头集合。 */
    private static final List<String> IDENTITY_HEADERS = List.of(
            "X-User-Id", "X-User-Role", "X-User-Type", "X-Session-Id");

    /** 网关认证配置。 */
    private final AuthProperties authProperties;
    /** auth-service 验证客户端。 */
    private final AuthServiceClient authServiceClient;
    /** Token 验证结果缓存。 */
    private final AuthCacheService authCacheService;
    /** 支持通配符白名单匹配的路径匹配器。 */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * 注入认证配置、认证服务客户端和验证缓存。
     */
    public AuthGlobalFilter(AuthProperties authProperties,
                            AuthServiceClient authServiceClient,
                            AuthCacheService authCacheService) {
        this.authProperties = authProperties;
        this.authServiceClient = authServiceClient;
        this.authCacheService = authCacheService;
    }

    /**
     * 按顺序处理白名单、凭据提取、缓存/回源验证和下游身份头传递。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isWhitelisted(path)) {
            return chain.filter(withoutIdentityHeaders(exchange));
        }

        String token = extractToken(exchange.getRequest());
        if (token == null) {
            return unauthorized(exchange.getResponse(), "缺少认证凭据");
        }

        // Redis 故障只视为缓存未命中，仍回源认证；认证服务不可用时 AuthServiceClient 返回无效结果并拒绝请求。
        return authCacheService.get(token)
                .onErrorResume(error -> {
                    LOGGER.warn("读取 Token 验证缓存失败: {}", error.getMessage());
                    return Mono.empty();
                })
                .switchIfEmpty(authServiceClient.verifyToken(token)
                        .flatMap(result -> authCacheService.put(token, result)
                                .onErrorResume(error -> {
                                    LOGGER.warn("写入 Token 验证缓存失败: {}", error.getMessage());
                                    return Mono.empty();
                                })
                                .thenReturn(result)))
                .flatMap(result -> isCompleteIdentity(result)
                        ? forwardAuthenticated(exchange, chain, result, token, path)
                        : unauthorized(exchange.getResponse(), "认证凭据无效或已过期"));
    }

    /**
     * 判断请求路径是否命中匿名白名单。
     */
    private boolean isWhitelisted(String path) {
        return authProperties.getWhitelist().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * 从标准 Authorization Header 提取非空 Bearer Token。
     */
    private String extractToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() || token.chars().anyMatch(Character::isWhitespace) ? null : token;
    }

    /**
     * 为认证通过的请求清除客户端身份头并注入认证服务返回的可信身份。
     */
    private Mono<Void> forwardAuthenticated(ServerWebExchange exchange, GatewayFilterChain chain,
                                            VerifyTokenResponse result, String token, String path) {
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();
        ServerHttpRequest request = requestBuilder
                .headers(headers -> IDENTITY_HEADERS.forEach(headers::remove))
                .header("X-User-Id", result.subject())
                .header("X-User-Role", result.role())
                .header("X-User-Type", result.type())
                .header("X-Session-Id", result.sessionId())
                .build();
        Mono<Void> forwarded = chain.filter(exchange.mutate().request(request).build());
        if ("/api/auth/logout".equals(path)) {
            // 注销完成后立即清除网关缓存，避免旧 Token 在缓存 TTL 内绕过撤销检查。
            return forwarded.then(authCacheService.evict(token).onErrorResume(error -> {
                LOGGER.warn("注销后清理 Token 验证缓存失败: {}", error.getMessage());
                return Mono.empty();
            }));
        }
        return forwarded;
    }

    /**
     * 认证服务返回 valid=true 时仍需检查身份字段完整，防止异常响应生成空 Header。
     */
    private boolean isCompleteIdentity(VerifyTokenResponse result) {
        return result.valid()
                && hasText(result.subject())
                && hasText(result.role())
                && hasText(result.type())
                && hasText(result.sessionId());
    }

    /**
     * 判断协议字段是否为非空文本。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 清理匿名请求中的身份头，防止白名单端点向下游传播伪造身份。
     */
    private ServerWebExchange withoutIdentityHeaders(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> IDENTITY_HEADERS.forEach(headers::remove))
                .build();
        return exchange.mutate().request(request).build();
    }

    /**
     * 返回统一 JSON 401 响应，不包含 Token 或其他敏感信息。
     */
    private Mono<Void> unauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
        String body = String.format("{\"code\":401,\"message\":\"%s\",\"data\":null}", message);
        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * 在路由转发前执行认证，保证下游只收到已验证身份。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
