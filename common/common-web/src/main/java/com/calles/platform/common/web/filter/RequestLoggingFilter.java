package com.calles.platform.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 统一记录 HTTP 入站与出站的访问日志过滤器。
 *
 * <p>核心定位与设计原则：
 * <ul>
 *   <li><b>执行时序</b>：排在 {@link TraceIdFilter} 和 {@link UserContextFilter} 之后执行，
 *       确保日志中已自动绑定 traceId 和已解析的用户上下文；</li>
 *   <li><b>安全与脱敏</b>：仅记录请求方式、URI 路径、响应状态码、客户端来源 IP 和执行耗时，
 *       严禁读取或序列化 Request Body，避免泄露用户密码/凭据或破坏大文件二进制流的传输；</li>
 *   <li><b>分级审计</b>：针对出站响应，状态码 &ge; 500 输出 ERROR，&ge; 400 输出 WARN，2xx/3xx 输出 INFO；</li>
 *   <li><b>降噪过滤</b>：高频的 Actuator 健康检查端点降级为 DEBUG，防止生产日志刷屏。</li>
 * </ul>
 * </p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** 健康检查探针端点路径前缀，用于识别高频探针请求并执行日志降噪。 */
    private static final String HEALTH_PATH = "/actuator/health";

    /**
     * 核心过滤逻辑：在请求进入和离开时分别记录访问日志并统计处理耗时。
     *
     * @param request 当前 HTTP 请求对象
     * @param response 当前 HTTP 响应对象
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException I/O 读写异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 步骤 1：提取请求基础元数据（HTTP 方法、请求 URI 与客户端来源 IP）
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String clientIp = resolveClientIp(request);

        // 步骤 2：识别当前请求是否为监控探针（如 K8s / Actuator 探针）
        boolean isHealthProbe = uri.startsWith(HEALTH_PATH);

        // 步骤 3：记录请求进入日志 [HTTP-IN]，并记录起始时间戳作为耗时基准
        long start = System.currentTimeMillis();
        if (isHealthProbe) {
            // 探针请求使用 DEBUG 级别，避免生产环境高频轮询污染日志
            LOGGER.debug("[HTTP-IN] {} {}, clientIp={}", method, uri, clientIp);
        } else {
            LOGGER.info("[HTTP-IN] {} {}, clientIp={}", method, uri, clientIp);
        }

        try {
            // 步骤 4：放行请求，交由后续过滤器链及 Spring MVC DispatcherServlet/Controller 业务处理
            filterChain.doFilter(request, response);
        } finally {
            // 步骤 5：在 finally 块中计算请求端到端总耗时，并捕获最终响应 HTTP 状态码
            long cost = System.currentTimeMillis() - start;
            int status = response.getStatus();

            // 步骤 6：依据响应状态码进行智能分级打印，确保异常请求在控制台突出显露
            if (isHealthProbe) {
                // 探针正常响应降噪
                LOGGER.debug("[HTTP-OUT] {} {} -> status={}, cost={}ms", method, uri, status, cost);
            } else if (status >= 500) {
                // 5xx 系统级严重故障，使用 ERROR 级别突出显示
                LOGGER.error("[HTTP-OUT] {} {} -> status={}, cost={}ms, clientIp={}", method, uri, status, cost, clientIp);
            } else if (status >= 400) {
                // 4xx 客户端/业务参数校验失败，使用 WARN 级别警示
                LOGGER.warn("[HTTP-OUT] {} {} -> status={}, cost={}ms, clientIp={}", method, uri, status, cost, clientIp);
            } else {
                // 2xx/3xx 正常业务处理，使用标准 INFO 级别
                LOGGER.info("[HTTP-OUT] {} {} -> status={}, cost={}ms, clientIp={}", method, uri, status, cost, clientIp);
            }
        }
    }

    /**
     * 解析客户端真实来源 IP。
     *
     * <p>解析策略如下：
     * <ul>
     *   <li>步骤 1：优先读取 X-Forwarded-For 请求头；若存在多级代理，截取第一个非空 IP（最原始客户端）；</li>
     *   <li>步骤 2：若无前置代理链路，尝试读取反向代理（如 Nginx）标准传递的 X-Real-IP；</li>
     *   <li>步骤 3：若均未提供，则回退获取底层 TCP 连接的远程套接字地址 getRemoteAddr()。</li>
     * </ul>
     * </p>
     *
     * @param request 当前 HTTP 请求
     * @return 解析出的客户端 IP 字符串，解析失败时兜底返回 "unknown"
     */
    private String resolveClientIp(HttpServletRequest request) {
        // 步骤 1：优先检查反向代理通过 X-Forwarded-For 携带的 IP 链路列表
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int commaIndex = forwarded.indexOf(',');
            // 多级代理场景下形如 "client, proxy1, proxy2"，首个 IP 为真实客户端
            return commaIndex > 0 ? forwarded.substring(0, commaIndex).trim() : forwarded.trim();
        }

        // 步骤 2：检查单一反向代理（如 Nginx / Ingress）传递的 X-Real-IP 请求头
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        // 步骤 3：兜底读取直连 TCP 套接字的 RemoteAddr
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
