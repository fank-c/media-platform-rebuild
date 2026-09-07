package com.calles.platform.auth.interfaces.http.dto;

import com.calles.platform.auth.application.AuthService;

/**
 * 登录或刷新成功后的凭据响应。客户端只应短期保存 accessToken，并以 refreshToken 完成轮换。
 */
public record TokenResponse(
        /** 用于携带 API 请求的短期 JWT。 */
        String accessToken,
        /** 一次性轮换凭据，客户端不得写入日志或拼接至 URL。 */
        String refreshToken,
        /** Access Token 的有效期秒数，不表示 Refresh Token 的有效期。 */
        long expiresIn,
        /** 当前签发时的账户角色。账户状态变更后须通过后续认证操作重新读取。 */
        String role
) {

    /** 将应用层令牌结果转换为 HTTP 契约，避免 Controller 依赖内部角色表示。 */
    public static TokenResponse from(AuthService.AuthTokens tokens) {
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), tokens.expiresIn(),
                tokens.role().getValue());
    }
}
