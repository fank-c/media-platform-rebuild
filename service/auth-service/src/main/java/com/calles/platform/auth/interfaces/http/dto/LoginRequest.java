package com.calles.platform.auth.interfaces.http.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录凭据请求。仅在请求处理期间保存明文密码，禁止记录到日志或响应中。
 */
public record LoginRequest(
        /** 登录邮箱由认证服务匹配，应用层会进行 trim 与转小写规范化。 */
        @NotBlank(message = "email 不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 255, message = "email 长度不能超过 255")
        String email,
        /** BCrypt 只能可靠处理最多 72 个 UTF-8 字节，应用层会作字节级复核。 */
        @NotBlank(message = "password 不能为空")
        @Size(max = 72, message = "password 长度不能超过 72 个字符")
        String password,
        /**
         * 客户端设备唯一标识，用于同设备重复登录时覆盖旧会话而非新增会话。
         * 字段可选：客户端不传时服务端自动生成随机 UUID，向后兼容旧版本客户端；
         * 新版本客户端应传入稳定的设备标识（如系统级设备 ID 或安全生成的持久化 UUID）。
         * 禁止将此字段值记录到日志或响应中。
         */
        @Size(max = 128, message = "deviceId 长度不能超过 128 个字符")
        String deviceId) {
}
