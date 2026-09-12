package com.calles.platform.common.web.filter;

import com.calles.platform.common.web.context.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("用户与设备上下文过滤器测试")
class UserContextFilterTest {

    private final UserContextFilter filter = new UserContextFilter();

    @Test
    @DisplayName("仅携带 X-Device-Id 请求头时成功初始化设备上下文并在请求结束后清理")
    void shouldInitializeDeviceContextWhenOnlyDeviceIdHeaderPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("X-Device-Id", "test-device-uuid-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean verifiedInChain = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> {
            assertThat(UserContext.getCurrentDeviceId()).contains("test-device-uuid-123");
            assertThat(UserContext.getCurrentUserId()).isEmpty();
            assertThat(UserContext.isAdmin()).isFalse();
            verifiedInChain.set(true);
        };

        filter.doFilter(request, response, chain);

        assertThat(verifiedInChain.get()).isTrue();
        assertThat(UserContext.get()).isEmpty();
    }

    @Test
    @DisplayName("同时携带身份头与 X-Device-Id 时完整初始化身份与设备上下文")
    void shouldInitializeFullContextWhenUserAndDeviceHeadersPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("X-User-Id", "usr-001");
        request.addHeader("X-User-Role", "ADMIN");
        request.addHeader("X-User-Type", "admin");
        request.addHeader("X-Session-Id", "sid-888");
        request.addHeader("X-Device-Id", "mobile-device-456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean verifiedInChain = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> {
            assertThat(UserContext.requireCurrentUserId()).isEqualTo("usr-001");
            assertThat(UserContext.getCurrentUserRole()).contains("ADMIN");
            assertThat(UserContext.getCurrentSessionId()).contains("sid-888");
            assertThat(UserContext.getCurrentDeviceId()).contains("mobile-device-456");
            assertThat(UserContext.isAdmin()).isTrue();
            verifiedInChain.set(true);
        };

        filter.doFilter(request, response, chain);

        assertThat(verifiedInChain.get()).isTrue();
        assertThat(UserContext.get()).isEmpty();
    }

    @Test
    @DisplayName("无身份头也无设备头时不初始化上下文")
    void shouldNotInitializeContextWhenHeadersAbsent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean verifiedInChain = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> {
            assertThat(UserContext.get()).isEmpty();
            assertThat(UserContext.getCurrentDeviceId()).isEmpty();
            verifiedInChain.set(true);
        };

        filter.doFilter(request, response, chain);

        assertThat(verifiedInChain.get()).isTrue();
        assertThat(UserContext.get()).isEmpty();
    }
}
