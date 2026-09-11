package com.calles.platform.gateway.filter;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 微服务网关全局请求访问日志与链路追踪过滤器（WebFlux 响应式）。
 *
 * <p>核心职责与设计原则：
 * <ul>
 *   <li><b>全局最外层入口</b>：实现 {@link Ordered} 并配置最高优先级（{@code HIGHEST_PRECEDENCE}），
 *       确保在网关认证拦截、路由寻址与熔断降级前生效；</li>
 *   <li><b>全链路 TraceId 补偿与透传</b>：如果客户端未携带 X-Trace-Id，则网关负责生成全局唯一 UUID，
 *       不仅注入下游转发请求头，还同时写回客户端响应头，实现端到端串联；</li>
 *   <li><b>非阻塞耗时度量</b>：在响应式数据流中通过 exchange attributes 记录初始时间戳，
 *       并在 {@code doFinally} 回调中完成终态耗时统计与日志打印，保障 WebFlux 高吞吐与响应式契约；</li>
 *   <li><b>状态码分级与监控探针降噪</b>：区分 5xx/4xx/2xx 级别输出，健康检查探针采用 DEBUG 级别避免刷屏。</li>
 * </ul>
 * </p>
 */
@Component
public class GatewayAccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayAccessLogFilter.class);

    /** 全链路追踪请求头名称。 */
    private static final String TRACE_HEADER = "X-Trace-Id";

    /** 网关上下文属性键：记录请求进入网关的时间戳（毫秒）。 */
    private static final String START_TIME_ATTR = "gatewayStartTime";

    /** 健康检查探针路径前缀。 */
    private static final String HEALTH_PATH = "/actuator/health";

    /**
     * 响应式网关核心过滤链处理。
     *
     * @param exchange 响应式 HTTP 请求/响应交互契约
     * @param chain 网关过滤器链
     * @return 异步完成信号 Mono
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 步骤 1：解析请求元数据（路径、HTTP 方法、真实客户端来源 IP）
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethod() != null ? request.getMethod().name() : "UNKNOWN";
        String clientIp = resolveClientIp(request);
        boolean isHealthProbe = path.startsWith(HEALTH_PATH);

        // 步骤 2：全链路 TraceId 初始化与传播
        // 若上游未携带 X-Trace-Id，则网关作为入口方自动生成全局唯一 TraceId
        ServerWebExchange targetExchange = exchange;
        String traceId = request.getHeaders().getFirst(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            // 通过 mutate() 产生不可变新请求并附加 X-Trace-Id 头
            ServerHttpRequest mutatedRequest = request.mutate().header(TRACE_HEADER, traceId).build();
            targetExchange = exchange.mutate().request(mutatedRequest).build();
        }
        // 将 TraceId 同步写入响应头，便于客户端/前端在出错时直接获取 TraceId 提交工单或排查
        targetExchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);

        // 步骤 3：在 WebFlux 上下文中存储起始时间戳，并打印进入日志 [GW-IN]
        long start = System.currentTimeMillis();
        targetExchange.getAttributes().put(START_TIME_ATTR, start);

        if (isHealthProbe) {
            LOGGER.debug("[GW-IN] {} {}, clientIp={}", method, path, clientIp);
        } else {
            LOGGER.info("[GW-IN] {} {}, clientIp={}", method, path, clientIp);
        }

        final ServerWebExchange finalExchange = targetExchange;

        // 步骤 4：执行后续网关过滤器链（鉴权、路由转发等）并在 doFinally 终止回调中收尾
        return chain.filter(finalExchange).doFinally(signalType -> {
            // 步骤 5：从 Exchange 上下文中取出起始时间戳，计算端到端网关总耗时
            Long startTime = finalExchange.getAttribute(START_TIME_ATTR);
            long cost = startTime != null ? System.currentTimeMillis() - startTime : 0;

            // 步骤 6：提取下游微服务响应或网关自身拦截产出的 HTTP 状态码
            HttpStatusCode statusCode = finalExchange.getResponse().getStatusCode();
            int status = statusCode != null ? statusCode.value() : 200;

            // 步骤 7：依据最终 HTTP 状态码进行分级日志输出
            if (isHealthProbe) {
                // 探针请求降级为 DEBUG
                LOGGER.debug("[GW-OUT] {} {} -> status={}, cost={}ms", method, path, status, cost);
            } else if (status >= 500) {
                // 5xx 网关转发失败或下游微服务内部报错
                LOGGER.error("[GW-OUT] {} {} -> status={}, cost={}ms, clientIp={}", method, path, status, cost, clientIp);
            } else if (status >= 400) {
                // 4xx 网关鉴权拒绝或下游参数/业务校验失败
                LOGGER.warn("[GW-OUT] {} {} -> status={}, cost={}ms, clientIp={}", method, path, status, cost, clientIp);
            } else {
                // 2xx/3xx 正常转发响应
                LOGGER.info("[GW-OUT] {} {} -> status={}, cost={}ms, clientIp={}", method, path, status, cost, clientIp);
            }
        });
    }

    /**
     * 解析 WebFlux 响应式请求中的客户端来源 IP。
     *
     * <p>解析策略：
     * <ul>
     *   <li>步骤 1：优先解析 X-Forwarded-For 请求头，截取第一个 IP 地址；</li>
     *   <li>步骤 2：次选读取 X-Real-IP 请求头；</li>
     *   <li>步骤 3：兜底获取 RemoteAddress 远端 Socket 地址。</li>
     * </ul>
     * </p>
     *
     * @param request 响应式请求对象
     * @return 客户端 IP 地址字符串
     */
    private String resolveClientIp(ServerHttpRequest request) {
        // 步骤 1：检查 X-Forwarded-For 代理链
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int commaIndex = forwarded.indexOf(',');
            return commaIndex > 0 ? forwarded.substring(0, commaIndex).trim() : forwarded.trim();
        }

        // 步骤 2：检查 X-Real-IP 标头
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        // 步骤 3：回退至底层 TCP 连接套接字地址
        return request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    /**
     * 配置过滤器执行顺序：网关最高优先级，确保最先进入并最后退出。
     *
     * @return 排序值 Ordered.HIGHEST_PRECEDENCE
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
