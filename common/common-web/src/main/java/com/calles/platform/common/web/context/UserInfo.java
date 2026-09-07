package com.calles.platform.common.web.context;

/**
 * 网关完成认证后传递给业务服务的用户身份快照。
 *
 * <p>该 DTO 只承载协议字段，不负责验签；业务服务仍应通过网络拓扑阻止客户端绕过网关直连。</p>
 */
public record UserInfo(
        /** 网关验签后传递的认证账户 ID。 */
        String userId,
        /** 账户角色，例如 USER 或 ADMIN。 */
        String role,
        /** 主体类型，例如 user 或 admin。 */
        String type,
        /** 与刷新会话关联的会话 ID。 */
        String sessionId) {

    /**
     * 判断身份是否具备管理员角色。
     */
    public boolean isAdmin() {
        return "admin".equals(type) || "ADMIN".equals(role);
    }

    /**
     * 判断身份是否为普通用户角色。
     */
    public boolean isUser() {
        return "user".equals(type) || "USER".equals(role);
    }
}
