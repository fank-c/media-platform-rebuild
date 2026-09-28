package com.calles.platform.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.gateway.config.AuthProperties;
import com.calles.platform.gateway.service.AuthCacheService;
import com.calles.platform.gateway.service.AuthServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关全局认证拦截器 (AuthGlobalFilter) 白名单放行与未认证拦截单元测试。
 */
class AuthGlobalFilterTest {

    private AuthProperties authProperties;
    private AuthServiceClient authServiceClient;
    private AuthCacheService authCacheService;
    private GatewayFilterChain filterChain;
    private AuthGlobalFilter filter;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        authServiceClient = mock(AuthServiceClient.class);
        authCacheService = mock(AuthCacheService.class);
        filterChain = mock(GatewayFilterChain.class);

        when(filterChain.filter(any())).thenReturn(Mono.empty());

        filter = new AuthGlobalFilter(authProperties, authServiceClient, authCacheService);
    }

    @Test
    @DisplayName("游客访问视频详情端点应命中白名单并放行，且剥离伪造身份头")
    void shouldAllowGuestAccessVideoDetailAndStripForgedHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/content/videos/cv05hG9Kq2RtLw7XbPmZv4Ya")
                .header("X-User-Id", "forged_user_999")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, filterChain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange forwarded = captor.getValue();
        assertNull(forwarded.getRequest().getHeaders().getFirst("X-User-Id"));
    }

    @Test
    @DisplayName("游客访问播放流切片端点应命中白名单放行")
    void shouldAllowGuestAccessVideoStreams() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/content/videos/cv05hG9Kq2RtLw7XbPmZv4Ya/streams").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, filterChain).block();

        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("游客访问单个视频公开统计端点应命中白名单放行")
    void shouldAllowGuestAccessSingleVideoStat() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/interactions/videos/cv05hG9Kq2RtLw7XbPmZv4Ya/stat").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, filterChain).block();

        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("游客访问批量视频统计端点应命中白名单放行")
    void shouldAllowGuestAccessBatchVideoStats() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/interactions/videos/stats").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, filterChain).block();

        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("游客访问首页推荐流应命中白名单放行")
    void shouldAllowGuestAccessRecommendFeed() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/recommend/feed").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, filterChain).block();

        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    @Test
    @DisplayName("游客访问心跳上报端点（非白名单）未携带 Token 应被拦截并返回 401 UNAUTHORIZED")
    void shouldRejectGuestHeartbeatWithoutToken() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/interactions/videos/cv05hG9Kq2RtLw7XbPmZv4Ya/heartbeat").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, filterChain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(filterChain, never()).filter(any());
    }

    @Test
    @DisplayName("游客访问点赞端点（非白名单）未携带 Token 应被拦截并返回 401 UNAUTHORIZED")
    void shouldRejectGuestLikeWithoutToken() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/interactions/videos/cv05hG9Kq2RtLw7XbPmZv4Ya/like").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, filterChain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(filterChain, never()).filter(any());
    }

    @Test
    @DisplayName("游客访问行为流水上报端点（非白名单）未携带 Token 应被拦截并返回 401 UNAUTHORIZED")
    void shouldRejectGuestFeedbackWithoutToken() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/recommend/feedback").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, filterChain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(filterChain, never()).filter(any());
    }
}
