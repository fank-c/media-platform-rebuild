package com.calles.platform.auth.interfaces.http.dto;

/**
 * Token 验证响应，包含用户身份信息。
 *
 * <p>验证结果说明：
 * <ul>
 *   <li>valid=true：Token 有效，其他字段包含用户身份信息</li>
 *   <li>valid=false：Token 无效（签名错误、已过期、已注销），其他字段均为 null</li>
 * </ul>
 *
 * <p>Token 无效的常见原因：
 * <ul>
 *   <li>JWT 签名验证失败</li>
 *   <li>Token 已过期（超过 expiresAt 时间）</li>
 *   <li>用户已注销（Token 在黑名单中）</li>
 *   <li>Token 格式错误</li>
 * </ul>
 */
public record VerifyTokenResponse(
        /** Token 是否有效 */
        boolean valid,

        /** 账户 ID（subject），Token 有效时非 null */
        String subject,

        /** 用户类型（user 或 admin），用于快速判断权限级别 */
        String type,

        /** 角色（USER 或 ADMIN），完整的角色枚举值 */
        String role,

        /** 会话 ID，用于关联会话状态和注销操作 */
        String sessionId,

        /** Token 过期时间戳（毫秒），用于判断剩余有效期 */
        Long expiresAt
) {
    /**
     * 创建一个表示 Token 无效的响应。
     * 所有身份信息字段均为 null。
     */
    public static VerifyTokenResponse invalid() {
        return new VerifyTokenResponse(false, null, null, null, null, null);
    }

    /**
     * 创建一个表示 Token 有效的响应。
     *
     * @param subject 账户 ID
     * @param type 用户类型（user 或 admin）
     * @param role 角色（USER 或 ADMIN）
     * @param sessionId 会话 ID
     * @param expiresAt Token 过期时间戳（毫秒）
     * @return Token 有效响应
     */
    public static VerifyTokenResponse valid(String subject, String type, String role,
                                           String sessionId, Long expiresAt) {
        return new VerifyTokenResponse(true, subject, type, role, sessionId, expiresAt);
    }
}
