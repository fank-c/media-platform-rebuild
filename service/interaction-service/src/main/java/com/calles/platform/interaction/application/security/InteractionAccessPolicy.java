package com.calles.platform.interaction.application.security;

import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.exception.InteractionException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * interaction-service 统一安全访问与权限校验策略。
 */
@Component
public class InteractionAccessPolicy {

    /**
     * 要求必须存在有效登录用户。
     *
     * @return 当前登录用户上下文
     * @throws InteractionException 当未登录时抛出 401 UNAUTHORIZED
     */
    public UserInfo requireUser() {
        return UserContext.get()
                .orElseThrow(() -> new InteractionException(HttpStatus.UNAUTHORIZED, "请先登录后再进行互动操作"));
    }

    /**
     * 获取当前操作主体身份 (支持匿名游客)。
     *
     * @return 当前登录用户上下文 Optional
     */
    public Optional<UserInfo> getCurrentUser() {
        return UserContext.get();
    }
}
