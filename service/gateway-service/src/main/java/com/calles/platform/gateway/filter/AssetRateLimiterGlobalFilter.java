package com.calles.platform.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 静态资源防盗刷限流过滤器。
 *
 * <p>职责：专门拦截针对静态资源受控代理接口（{@code /api/files/assets/**}）的恶意爬取与高频盗刷请求，
 * 基于客户端真实 IP 执行秒级原子计数限流。
 *
 * <p>所属边界：gateway-service 网关流量防护层。
 * 主要协作对象：{@link ReactiveStringRedisTemplate}。
 * 容灾策略：若 Redis 通信异常或故障，执行优雅降级（fail-open）放行，确保正常业务不因限流组件故障而中断。
 */
@Component
public class AssetRateLimiterGlobalFilter implements GlobalFilter, Ordered {

    /** 日志记录器。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AssetRateLimiterGlobalFilter.class);

    /** 受保护的静态资源路径匹配模式。 */
    private static final String ASSETS_PATH_PATTERN = "/api/files/assets/**";

    /** Redis 限流 Key 前缀。 */
    private static final String RATE_LIMIT_PREFIX = "gateway:ratelimit:assets:";

    /** 单个客户端 IP 每秒允许的最大请求数。 */
    private static final long MAX_REQUESTS_PER_SECOND = 30L;

    /** 路径匹配器。 */
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** 响应式 Redis 客户端。 */
    private final ReactiveStringRedisTemplate redisTemplate;

    /**
     * 构造静态资源限流过滤器。
     *
     * @param redisTemplate 响应式 Redis 模板
     */
    public AssetRateLimiterGlobalFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 步骤 1：仅对静态资源受控代理路径执行频次限流
        if (!pathMatcher.match(ASSETS_PATH_PATTERN, path)) {
            return chain.filter(exchange);
        }

        // 步骤 2：解析客户端真实来源 IP
        String clientIp = resolveClientIp(exchange.getRequest());
        long currentSecond = System.currentTimeMillis() / 1000;
        String limitKey = RATE_LIMIT_PREFIX + clientIp + ":" + currentSecond;

        // 步骤 3：基于 Redis 原子累加计算当前秒请求计数，并设置 2 秒过期
        return redisTemplate.opsForValue().increment(limitKey)
                .flatMap(count -> {
                    if (count == 1) {
                        // 首次设置 Key 时附加过期时间
                        return redisTemplate.expire(limitKey, Duration.ofSeconds(2))
                                .thenReturn(count);
                    }
                    return Mono.just(count);
                })
                .flatMap(count -> {
                    // 步骤 4：判断是否超出限流阈值
                    if (count > MAX_REQUESTS_PER_SECOND) {
                        LOGGER.warn("静态资源防盗刷限流拦截: clientIp={}, count={}, path={}", clientIp, count, path);
                        return tooManyRequests(exchange, "静态资源请求过于频繁，请稍后再试");
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(exception -> {
                    // 步骤 5：Redis 故障或不可达时优雅降级放行，保障用户体验
                    LOGGER.warn("静态资源限流组件 Redis 通信异常，执行降级放行: error={}", exception.getMessage());
                    return chain.filter(exchange);
                });
    }

    /**
     * 构造并返回 HTTP 429 Too Many Requests 响应。
     */
    private Mono<Void> tooManyRequests(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
        String body = String.format("{\"code\":429,\"message\":\"%s\",\"data\":null}", message);
        return response.writeWith(Mono.just(response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * 解析客户端来源 IP，优先读取代理头。
     */
    private String resolveClientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int commaIndex = forwarded.indexOf(',');
            return commaIndex > 0 ? forwarded.substring(0, commaIndex).trim() : forwarded.trim();
        }
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    /**
     * 优先级高于网关认证过滤器（-100），在最外层即阻断高频盗刷流量。
     */
    @Override
    public int getOrder() {
        return -150;
    }
}
