package com.calles.platform.gateway.dto;

/**
 * auth-service /verify 的稳定响应投影，避免网关依赖认证服务内部 DTO。
 */
public record VerifyTokenResponse(
        /** Token 是否通过 auth-service 验证。 */
        boolean valid,
        /** 认证账户 ID。 */
        String subject,
        /** 主体类型（user 或 admin）。 */
        String type,
        /** 账户角色（USER 或 ADMIN）。 */
        String role,
        /** 刷新会话 ID。 */
        String sessionId,
        /** Access Token 过期时间戳，单位为毫秒。 */
        Long expiresAt) {

    /**
     * 创建不携带身份信息的无效验证结果。
     */
    public static VerifyTokenResponse invalid() {
        return new VerifyTokenResponse(false, null, null, null, null, null);
    }
}
