package com.calles.platform.auth.interfaces.http.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Token 验证请求，供网关和其他服务调用。
 *
 * <p>使用场景：
 * <ul>
 *   <li>API 网关验证请求中的 Token 合法性</li>
 *   <li>服务间调用时验证调用方身份</li>
 *   <li>不建议前端直接调用（前端应使用 /me 接口获取用户信息）</li>
 * </ul>
 *
 * <p>Token 格式：
 * <ul>
 *   <li>可以是完整的 "Bearer eyJ..." 格式</li>
 *   <li>也可以是裸 Token "eyJ..."</li>
 *   <li>验证逻辑会自动处理这两种格式</li>
 * </ul>
 */
public record VerifyTokenRequest(
        /** 待验证的访问令牌，可以带或不带 "Bearer " 前缀 */
        @NotBlank(message = "token 不能为空")
        String token
) {
}
