package com.calles.platform.gateway.service;

import com.calles.platform.gateway.config.AuthProperties;
import com.calles.platform.gateway.dto.VerifyTokenResponse;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 认证服务客户端，仅调用内部 /api/auth/verify，不记录或回传完整 Token。
 */
@Service
public class AuthServiceClient {

    /** 认证调用日志记录器，不输出令牌原文。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthServiceClient.class);
    /** 认证服务调用的最大等待时间，超时即按认证失败处理。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final WebClient webClient;

    /**
     * 使用带服务发现的 WebClient 构造认证服务调用端点。
     */
    public AuthServiceClient(
            @Qualifier("loadBalancedWebClientBuilder") WebClient.Builder webClientBuilder,
            AuthProperties authProperties) {
        this.webClient = webClientBuilder.baseUrl(authProperties.getAuthServiceUrl()).build();
    }

    /**
     * 将访问令牌交由认证服务验签；认证服务异常时返回无效结果，网关按失败关闭请求。
     */
    public Mono<VerifyTokenResponse> verifyToken(String token) {
        return webClient.post()
                .uri("/api/auth/verify")
                .bodyValue(Map.of("token", token))
                .retrieve()
                .bodyToMono(VerifyResponse.class)
                .timeout(REQUEST_TIMEOUT)
                .map(response -> response.data() == null
                        ? VerifyTokenResponse.invalid()
                        : response.data())
                .doOnError(error -> LOGGER.warn("Token 验证请求失败: {}", error.getMessage()))
                .onErrorReturn(VerifyTokenResponse.invalid());
    }

    /** auth-service 统一响应结构的本地投影。 */
    private record VerifyResponse(
            /** auth-service 响应码。 */
            int code,
            /** auth-service 响应消息。 */
            String message,
            /** Token 验证结果。 */
            VerifyTokenResponse data) {
    }
}
