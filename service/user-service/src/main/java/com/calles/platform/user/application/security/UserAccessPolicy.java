package com.calles.platform.user.application.security;
import com.calles.platform.user.exception.UserProfileException;

import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.common.web.context.UserInfo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * user-service 范围的显式入口权限策略，不扩散为公共 AOP 或改变现有认证语义。
 */
@Component
public class UserAccessPolicy {

    /** 要求任意已认证主体。 */
    public UserInfo requireAuthenticated() {
        return UserContext.get().orElseThrow(() -> new UserProfileException(HttpStatus.UNAUTHORIZED, "缺少有效身份"));
    }

    /** 要求普通用户身份，管理员不得借本人接口自动创建普通资料。 */
    public UserInfo requireUser() {
        UserInfo user = requireAuthenticated();
        if (!user.isUser() || user.isAdmin()) {
            throw new UserProfileException(HttpStatus.FORBIDDEN, "当前身份不能使用普通用户资料接口");
        }
        return user;
    }

    /** 要求管理员身份。 */
    public UserInfo requireAdmin() {
        UserInfo user = requireAuthenticated();
        if (!user.isAdmin()) {
            throw new UserProfileException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return user;
    }
}
