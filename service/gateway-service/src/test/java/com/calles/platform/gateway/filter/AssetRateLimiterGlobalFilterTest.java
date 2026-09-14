package com.calles.platform.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** 静态资源防盗刷限流过滤器单元测试。 */
class AssetRateLimiterGlobalFilterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveValueOperations<String, String> valueOperations;
    private GatewayFilterChain filterChain;
    private AssetRateLimiterGlobalFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        valueOperations = mock(ReactiveValueOperations.class);
        filterChain = mock(GatewayFilterChain.class);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        filter = new AssetRateLimiterGlobalFilter(redisTemplate);
    }

    @Test
    @DisplayName("非静态资源路径应直接放行，不触发 Redis 限流操作")
    void shouldPassNonAssetRequests() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/users/profile").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, filterChain).block();

        verify(filterChain).filter(exchange);
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    @DisplayName("静态资源请求在阈值内应正常放行")
    void shouldPassAssetRequestWithinLimit() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/files/assets/pic-123").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(valueOperations.increment(anyString())).thenReturn(Mono.just(1L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        filter.filter(exchange, filterChain).block();

        verify(filterChain).filter(exchange);
    }

    @Test
    @DisplayName("单个 IP 静态资源请求超过 30 次应拦截并返回 429 Too Many Requests")
    void shouldBlockWhenRateLimitExceeded() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/files/assets/pic-123")
                .header("X-Forwarded-For", "192.168.1.100")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // 模拟当前秒计数已累加到 31 次（超过 30 次上限）
        when(valueOperations.increment(anyString())).thenReturn(Mono.just(31L));

        filter.filter(exchange, filterChain).block();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        verify(filterChain, never()).filter(exchange);
    }

    @Test
    @DisplayName("Redis 异常时应优雅降级放行，保障正常业务连续性")
    void shouldFailOpenWhenRedisErrors() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/files/assets/pic-123").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        when(valueOperations.increment(anyString())).thenReturn(Mono.error(new RuntimeException("Redis connection timeout")));

        filter.filter(exchange, filterChain).block();

        verify(filterChain).filter(exchange);
    }
}
