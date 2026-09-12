package com.calles.platform.common.web.filter;

import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.common.web.context.UserInfo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 将网关注入的认证 Header 转换为业务服务可访问的 ThreadLocal 上下文。
 *
 * <p>过滤器不自行信任或验证 JWT；直连防护和网关验签属于部署及网关职责。无用户 ID 时不创建
 * 上下文，允许健康检查等无需身份的请求继续处理。</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class UserContextFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String HEADER_USER_TYPE = "X-User-Type";
    private static final String HEADER_SESSION_ID = "X-Session-Id";
    private static final String HEADER_DEVICE_ID = "X-Device-Id";

    /**
     * 创建无状态过滤器；请求级身份与设备元数据仅保存在 UserContext 中。
     */
    public UserContextFilter() {
    }

    /**
     * 解析请求身份及设备标识、执行后续过滤器并在 finally 中清理线程上下文。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String userId = request.getHeader(HEADER_USER_ID);
            String deviceId = request.getHeader(HEADER_DEVICE_ID);
            if ((userId != null && !userId.isBlank()) || (deviceId != null && !deviceId.isBlank())) {
                UserContext.set(new UserInfo(
                        userId,
                        request.getHeader(HEADER_USER_ROLE),
                        request.getHeader(HEADER_USER_TYPE),
                        request.getHeader(HEADER_SESSION_ID),
                        deviceId));
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
