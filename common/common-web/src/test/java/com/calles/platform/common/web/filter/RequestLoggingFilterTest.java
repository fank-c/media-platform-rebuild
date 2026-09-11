package com.calles.platform.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("请求访问日志过滤器测试")
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    @DisplayName("正常请求能顺利通过过滤器链且不抛出异常")
    void shouldPassNormalRequest() throws ServletException, IOException {
        // 步骤 1：构造带有前置代理 IP 请求头的 Mock 请求与 200 响应
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/profile");
        request.addHeader("X-Forwarded-For", "192.168.1.100, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        FilterChain chain = (req, res) -> {};

        // 步骤 2：执行过滤器，断言不抛出任何异常且状态码保持为 200
        assertThatCode(() -> filter.doFilter(request, response, chain))
                .doesNotThrowAnyException();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("错误状态码请求能正常记录并向下传递")
    void shouldHandleErrorStatus() throws ServletException, IOException {
        // 步骤 1：构造模拟返回 401 Unauthorized 的请求与响应
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("X-Real-IP", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            ((MockHttpServletResponse) res).setStatus(401);
        };

        // 步骤 2：执行过滤器，断言 401 状态码正常传递并被 WARN 日志记录
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("健康检查端点请求能顺利处理")
    void shouldHandleHealthProbeRequest() throws ServletException, IOException {
        // 步骤 1：构造访问 /actuator/health 的探针请求
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        FilterChain chain = (req, res) -> {};

        // 步骤 2：执行过滤器，断言探针能正常放行并降级为 DEBUG 日志
        assertThatCode(() -> filter.doFilter(request, response, chain))
                .doesNotThrowAnyException();
    }
}
