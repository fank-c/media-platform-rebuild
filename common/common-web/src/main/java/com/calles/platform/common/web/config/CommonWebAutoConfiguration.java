package com.calles.platform.common.web.config;

import com.calles.platform.common.web.filter.RequestLoggingFilter;
import com.calles.platform.common.web.filter.TraceIdFilter;
import com.calles.platform.common.web.filter.UserContextFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * common-web 的自动配置入口，为 Servlet 业务服务注册用户上下文和请求日志过滤器。
 *
 * <p>网关是 WebFlux 应用，不依赖 common-web；只有引入该模块的业务服务会启用此过滤器。</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

    /**
     * 注册请求追踪过滤器，确保业务日志和注册事件在无上游标识时仍具有 traceId。
     *
     * @return 无状态 traceId 过滤器
     */
    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    /**
     * 注册请求访问日志过滤器，统一记录出入站状态、耗时与来源 IP。
     *
     * @return 无状态请求日志过滤器
     */
    @Bean
    public RequestLoggingFilter requestLoggingFilter() {
        return new RequestLoggingFilter();
    }

    /**
     * 注册单例请求上下文过滤器。
     *
     * @return 无状态用户上下文过滤器
     */
    @Bean
    public UserContextFilter userContextFilter() {
        return new UserContextFilter();
    }
}
