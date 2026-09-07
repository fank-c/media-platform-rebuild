package com.calles.platform.auth.interfaces.http.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。当前仅支持用户角色注册，管理员账户由运维创建。
 *
 * <p>密码长度限制说明：
 * <ul>
 *   <li>最小 8 字符：满足基本安全要求</li>
 *   <li>最大 72 字符：BCrypt 算法的硬性限制（只处理前 72 个 UTF-8 字节）</li>
 * </ul>
 *
 * <p>登录名规则：
 * <ul>
 *   <li>只能包含字母、数字、下划线和连字符</li>
 *   <li>长度 3-255 字符</li>
 *   <li>大小写敏感，但会统一 trim 处理</li>
 * </ul>
 */
public record RegisterRequest(
        /** 登录名，用于登录认证，全局唯一 */
        @NotBlank(message = "loginName 不能为空")
        @Size(min = 3, max = 255, message = "loginName 长度必须在 3-255 之间")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "loginName 只能包含字母、数字、下划线和连字符")
        String loginName,

        /** 明文密码，传输后立即 BCrypt 加密存储，不可逆 */
        @NotBlank(message = "password 不能为空")
        @Size(min = 8, max = 72, message = "password 长度必须在 8-72 个字符之间")
        String password
) {
}
