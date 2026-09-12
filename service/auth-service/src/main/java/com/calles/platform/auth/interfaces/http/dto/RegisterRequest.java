package com.calles.platform.auth.interfaces.http.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
 * <p>邮箱规则：
 * <ul>
 *   <li>符合标准邮箱格式</li>
 *   <li>长度不超过 255 字符</li>
 *   <li>应用层统一进行 trim 和转小写（toLowerCase）规范化处理</li>
 * </ul>
 */
public record RegisterRequest(
        /** 注册邮箱，用于身份认证，全局唯一 */
        @NotBlank(message = "email 不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 255, message = "email 长度不能超过 255")
        String email,

        /** 明文密码，传输后立即 BCrypt 加密存储，不可逆 */
        @NotBlank(message = "password 不能为空")
        @Size(min = 8, max = 72, message = "password 长度必须在 8-72 个字符之间")
        String password
) {
}
