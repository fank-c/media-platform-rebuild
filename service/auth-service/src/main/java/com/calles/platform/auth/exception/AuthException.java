package com.calles.platform.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * 认证领域可预期错误。错误消息只表达处理结果，不泄露密码、Token 或账户敏感信息。
 */
public class AuthException extends RuntimeException {

    /** 对外 HTTP 状态，必须与认证失败的可恢复性保持一致。 */
    private final HttpStatus status;
    /** 稳定的认证域错误码，供调用方分类处理，不能包含敏感内容。 */
    private final String errorCode;

    /**
     * 构造预期的认证失败。{@code message} 可对客户端公开，禁止传入密码、完整 Token 或底层异常文本。
     */
    public AuthException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    /** 返回可对外响应的 HTTP 状态。 */
    public HttpStatus getStatus() {
        return status;
    }

    /** 返回供调用方分类的稳定认证错误码。 */
    public String getErrorCode() {
        return errorCode;
    }

    /** 请求字段不符合认证域规则。 */
    public static AuthException invalidRequest(String message) {
        return new AuthException(HttpStatus.BAD_REQUEST, "AUTH_INVALID_REQUEST", message);
    }

    /** 统一登录失败响应，避免泄露账号是否存在。 */
    public static AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "账号或密码错误");
    }

    /** 已禁用账户的明确拒绝响应。 */
    public static AuthException accountDisabled() {
        return new AuthException(HttpStatus.FORBIDDEN, "AUTH_ACCOUNT_DISABLED", "账号已禁用");
    }

    /** Access Token 缺失、过期、伪造或被撤销。 */
    public static AuthException invalidAccessToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_ACCESS_TOKEN", "认证凭据无效或已过期");
    }

    /** Refresh Token 缺失、过期、轮换后重放或所属会话已注销。 */
    public static AuthException invalidRefreshToken() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_REFRESH_TOKEN", "刷新凭据无效或已失效");
    }

    /** Redis 会话状态无法可靠读写时的拒绝响应，避免错误放行认证请求。 */
    public static AuthException sessionUnavailable() {
        return new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_SESSION_UNAVAILABLE", "认证会话暂不可用");
    }

    /** 邮箱已被注册。数据库唯一索引仍是并发注册时的最终约束。 */
    public static AuthException emailAlreadyExists() {
        return new AuthException(HttpStatus.CONFLICT, "AUTH_EMAIL_EXISTS", "该邮箱已注册");
    }
}
