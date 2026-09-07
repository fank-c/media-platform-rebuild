package com.calles.platform.auth.interfaces.http.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新访问令牌的请求。刷新令牌是一次性消费的敏感凭据，不能写入日志或错误消息。
 */
public record RefreshRequest(
        /** 不透明刷新令牌原值，由服务端哈希后查询 Redis 会话。 */
        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken) {
}
