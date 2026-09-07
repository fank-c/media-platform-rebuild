package com.calles.platform.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为 Servlet 入站请求校验或生成 traceId，并在响应、日志 MDC 和下游业务事件之间传播。
 *
 * <p>该过滤器只处理追踪标识，不读取认证身份，也不承担分布式追踪采样和导出。</p>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** HTTP 链路追踪 Header 名称。 */
    private static final String TRACE_HEADER = "X-Trace-Id";
    /** 限制外部追踪标识的字符和长度，避免将任意文本写入日志上下文。 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** 创建无状态追踪过滤器，请求状态仅存在于当前线程 MDC。 */
    public TraceIdFilter() {
    }

    /**
     * 在业务过滤器前建立 traceId，并在请求完成后清理线程本地状态防止线程复用串号。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 后续 Servlet 处理失败
     * @throws IOException 响应或过滤器链发生 I/O 失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_HEADER));
        MDC.put("traceId", traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    /** 外部标识合法时继续传播，否则生成新的不可预测 UUID。 */
    private String resolveTraceId(String candidate) {
        if (candidate != null && SAFE_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
