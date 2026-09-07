package com.calles.platform.auth.interfaces.http.dto;

/**
 * 当前登录用户信息响应。
 *
 * <p>通过 Authorization Header 中的 Token 解析当前用户身份。
 * 该接口用于前端获取当前登录用户的基本信息。
 *
 * <p>与 Token 验证接口的区别：
 * <ul>
 *   <li>/verify：供服务间调用，返回 valid 字段，Token 无效时返回 valid=false</li>
 *   <li>/me：供前端调用，Token 无效时直接返回 401 错误</li>
 * </ul>
 *
 * <p>使用场景：
 * <ul>
 *   <li>前端页面初始化时获取用户信息</li>
 *   <li>用户刷新页面后恢复登录状态</li>
 *   <li>验证 Token 是否仍然有效</li>
 * </ul>
 */
public record CurrentUserResponse(
        /** 账户 ID（UUID 格式） */
        String accountId,

        /** 登录名 */
        String loginName,

        /** 角色（USER 或 ADMIN） */
        String role,

        /** 用户类型（user 或 admin），用于前端快速判断权限 */
        String type,

        /** 会话 ID，用于后续注销操作 */
        String sessionId
) {
}
