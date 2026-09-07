package com.calles.platform.common.web.context;

import java.util.Optional;

/**
 * 当前 HTTP 请求的用户上下文持有者。
 *
 * <p>由 {@code UserContextFilter} 写入并在请求结束时清理。异步任务和消息消费者脱离请求线程，
 * 必须显式传递身份，不能依赖此 ThreadLocal。</p>
 */
public final class UserContext {

    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    private UserContext() {
        // 工具类禁止实例化。
    }

    /**
     * 设置当前线程身份，仅供 Web 横切层使用。
     *
     * @param userInfo 已由网关传入的身份快照
     */
    public static void set(UserInfo userInfo) {
        CONTEXT.set(userInfo);
    }

    /**
     * 获取当前请求身份。
     *
     * @return 未认证或未经过上下文过滤时返回空
     */
    public static Optional<UserInfo> get() {
        return Optional.ofNullable(CONTEXT.get());
    }

    /**
     * 获取当前用户 ID。
     */
    public static Optional<String> getCurrentUserId() {
        return get().map(UserInfo::userId);
    }

    /**
     * 获取当前用户 ID，不存在时拒绝继续执行业务逻辑。
     *
     * @throws IllegalStateException 当前请求没有用户身份
     */
    public static String requireCurrentUserId() {
        return getCurrentUserId().orElseThrow(() ->
                new IllegalStateException("当前请求未包含用户身份信息"));
    }

    /**
     * 获取当前用户角色。
     */
    public static Optional<String> getCurrentUserRole() {
        return get().map(UserInfo::role);
    }

    /**
     * 获取当前会话 ID。
     */
    public static Optional<String> getCurrentSessionId() {
        return get().map(UserInfo::sessionId);
    }

    /**
     * 判断当前请求是否为管理员身份。
     */
    public static boolean isAdmin() {
        return get().map(UserInfo::isAdmin).orElse(false);
    }

    /**
     * 清理当前线程身份，避免容器线程复用造成身份串线或内存泄漏。
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
