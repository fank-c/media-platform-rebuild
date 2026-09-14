package com.calles.platform.content.application.security;

import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.content.exception.ContentException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 内容服务统一入口访问与鉴权安全策略组件。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：位于应用层安全门禁边界，负责从 Web 线程上下文中提取并判定调用方身份与权限；</li>
 *   <li><b>协作对象</b>：基于 {@code common-web} 提供的 {@link UserContext} 与 {@link UserInfo}，不依赖重量级安全框架，保持轻量；</li>
 *   <li><b>安全规则</b>：严格区分匿名访问、登录用户访问、视频创作者归属校验以及平台治理管理员访问。</li>
 * </ul>
 * </p>
 */
@Component
public class ContentAccessPolicy {

    /**
     * 获取当前操作主体（若存在）；未登录或匿名访客请求时返回空 Optional。
     *
     * @return 包含当前认证用户上下文的 {@link Optional}，未认证时为 {@link Optional#empty()}
     */
    public Optional<UserInfo> getCurrentUser() {
        return UserContext.get();
    }

    /**
     * 断言当前请求必须携带有效认证身份，否则阻断并抛出未授权异常。
     *
     * @return 当前登录用户的上下文信息
     * @throws ContentException 当未获取到有效 Token 或用户上下文时抛出 401 UNAUTHORIZED
     */
    public UserInfo requireAuthenticated() {
        return UserContext.get()
                .orElseThrow(() -> new ContentException(HttpStatus.UNAUTHORIZED, "缺少有效身份凭据"));
    }

    /**
     * 要求调用方具备正常普通用户登录身份。
     *
     * @return 当前普通用户的上下文信息
     * @throws ContentException 当未认证时抛出 401 UNAUTHORIZED
     */
    public UserInfo requireUser() {
        return requireAuthenticated();
    }

    /**
     * 要求平台治理管理员身份，普通用户无权通行。
     *
     * @return 当前管理员用户的上下文信息
     * @throws ContentException 当未认证抛出 401，非管理员角色抛出 403 FORBIDDEN
     */
    public UserInfo requireAdmin() {
        UserInfo user = requireAuthenticated();
        if (!user.isAdmin()) {
            throw new ContentException(HttpStatus.FORBIDDEN, "需要平台管理员权限");
        }
        return user;
    }

    /**
     * 校验当前调用主体必须为指定视频的创作者本人或平台超级管理员。
     *
     * @param authorId 目标视频聚合根中记录的创作者账号 ID
     * @return 验证通过的当前操作主体信息
     * @throws ContentException 当未登录抛出 401，非本人且非管理员时抛出 403 FORBIDDEN
     */
    public UserInfo requireOwnerOrAdmin(String authorId) {
        UserInfo user = requireAuthenticated();
        if (authorId == null || (!user.isAdmin() && !user.userId().equals(authorId))) {
            throw new ContentException(HttpStatus.FORBIDDEN, "无权操作非本人拥有的视频内容");
        }
        return user;
    }
}
