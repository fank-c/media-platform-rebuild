package com.calles.platform.auth.interfaces.http.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录凭据请求。仅在请求处理期间保存明文密码，禁止记录到日志或响应中。
 */
public record LoginRequest(
        /** 登录名由认证服务按精确值匹配，应用层会移除首尾空白。 */
        @NotBlank(message = "loginName 不能为空")
        @Size(max = 255, message = "loginName 长度不能超过 255")
        String loginName,
        /** BCrypt 只能可靠处理最多 72 个 UTF-8 字节，应用层会作字节级复核。 */
        @NotBlank(message = "password 不能为空")
        @Size(max = 72, message = "password 长度不能超过 72 个字符")
        String password) {
}
