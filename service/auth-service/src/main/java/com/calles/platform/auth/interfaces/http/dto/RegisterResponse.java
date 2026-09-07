package com.calles.platform.auth.interfaces.http.dto;

/**
 * 注册成功响应。不包含敏感信息。
 *
 * <p>返回的账户信息仅用于确认注册成功，不包含：
 * <ul>
 *   <li>密码哈希</li>
 *   <li>Token（需通过 /login 接口获取）</li>
 *   <li>会话信息</li>
 * </ul>
 */
public record RegisterResponse(
        /** 账户 ID（UUID 格式，无连字符的 32 位十六进制字符串） */
        String accountId,

        /** 登录名（已规范化处理） */
        String loginName,

        /** 角色（USER 或 ADMIN），当前注册接口固定返回 USER */
        String role,

        /** 账户状态（ACTIVE 或 DISABLED），新注册账户固定为 ACTIVE */
        String status
) {
}
