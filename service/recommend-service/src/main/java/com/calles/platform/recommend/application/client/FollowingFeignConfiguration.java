package com.calles.platform.recommend.application.client;

import feign.Request;
import feign.Retryer;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;

/**
 * 关注召回专用 Feign 配置。
 *
 * <p>仅绑定关注作者和互动统计两个客户端，避免修改内容客户端及其他全局调用的重试与超时。</p>
 */
public class FollowingFeignConfiguration {

    /**
     * 禁止请求内重试，避免在首页 500ms 预算内放大依赖请求。
     *
     * @return 永不重试策略
     */
    @Bean
    public Retryer followingRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 设置关注召回客户端的连接和读取超时。
     *
     * @return 客户端超时配置
     */
    @Bean
    public Request.Options followingRequestOptions() {
        return new Request.Options(50, TimeUnit.MILLISECONDS, 100, TimeUnit.MILLISECONDS, true);
    }

    /**
     * 将当前线程的 traceId 透传给下游服务。
     *
     * @return 请求拦截器
     */
    @Bean
    public RequestInterceptor followingTraceIdInterceptor() {
        return (RequestTemplate template) -> {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                template.header("X-Trace-Id", traceId);
            }
        };
    }
}
