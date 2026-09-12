package com.calles.platform.auth.interfaces.http;

import com.calles.platform.auth.application.AuthService;
import com.calles.platform.auth.interfaces.http.dto.CurrentUserResponse;
import com.calles.platform.auth.interfaces.http.dto.LoginRequest;
import com.calles.platform.auth.interfaces.http.dto.RefreshRequest;
import com.calles.platform.auth.interfaces.http.dto.RegisterRequest;
import com.calles.platform.auth.interfaces.http.dto.RegisterResponse;
import com.calles.platform.auth.interfaces.http.dto.TokenResponse;
import com.calles.platform.auth.interfaces.http.dto.VerifyTokenRequest;
import com.calles.platform.auth.interfaces.http.dto.VerifyTokenResponse;
import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证域的 HTTP 适配器。
 *
 * <p>仅负责请求校验、协议转换和响应封装；账户状态、凭据校验、令牌签发及会话失效均由
 * {@link AuthService} 处理，以避免 HTTP 层承载认证规则。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 应用层用例入口，HTTP 层不直接访问账户表或 Redis。 */
    private final AuthService authService;

    /**
     * @param authService 认证域应用服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 不携带凭据的服务连通性探针。 */
    @GetMapping("/ping")
    public ApiResponse<String> ping() {
        return ApiResponse.ok("auth-service");
    }

    /**
     * 校验登录凭据并签发新的访问令牌和刷新令牌。
     * 设备标识优先从 UserContext（由 X-Device-Id 请求头拦截注入）读取，未携带时检查请求头或回退至请求体 deviceId；
     * 均缺失时由服务端生成随机 UUID 向后兼容。
     */
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(
            @RequestHeader(value = "X-Device-Id", required = false) String deviceIdHeader,
            @Valid @RequestBody LoginRequest request) {
        String deviceId = UserContext.getCurrentDeviceId()
                .filter(id -> !id.isBlank())
                .or(() -> java.util.Optional.ofNullable(deviceIdHeader).filter(id -> !id.isBlank()))
                .orElse(request.deviceId());
        return ApiResponse.ok(TokenResponse.from(authService.login(request.email(), request.password(), deviceId)));
    }

    /**
     * 原子消费刷新令牌并完成令牌轮换。旧刷新令牌无论后续账户校验结果如何均不可再次使用。
     */
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(TokenResponse.from(authService.refresh(request.refreshToken())));
    }

    /**
     * 注销当前会话，并将当前访问令牌的 jti 标记为失效直至其自然过期。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(authorization);
        return ApiResponse.ok();
    }

    /** 注册普通用户账户；管理员账户不经由此公开接口创建。 */
    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request.email(), request.password()));
    }

    /**
     * 供网关使用的令牌验证接口。无效令牌返回 {@code valid=false}，不将 JWT 解析异常暴露给调用方。
     */
    @PostMapping("/verify")
    public ApiResponse<VerifyTokenResponse> verify(@Valid @RequestBody VerifyTokenRequest request) {
        return ApiResponse.ok(authService.verifyToken(request.token()));
    }

    /**
     * 返回当前账户的实时状态和角色。与 {@code /verify} 不同，本接口会读取认证账户表。
     */
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> getCurrentUser(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ApiResponse.ok(authService.getCurrentUser(authorization));
    }
}
