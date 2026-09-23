package com.calles.platform.user.domain.follow.event;

/**
 * 作者维度交互行为事件载荷，契约版本 v1。
 * 与推荐系统交互流规范完全对齐。
 *
 * @param userId 行为发起人账号 ID (关注者)
 * @param authorId 目标创作者账号 ID (被关注者)
 * @param action 交互动作类型 (固定为 FOLLOW)
 * @param state 动作生效状态 (ACTIVE: 已关注, INACTIVE: 已取消关注)
 */
public record AuthorActionPayload(
        String userId,
        String authorId,
        String action,
        String state
) {
    public static final String ACTION_FOLLOW = "FOLLOW";

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_INACTIVE = "INACTIVE";

    /**
     * 构造关注生效载荷。
     *
     * @param userId 关注发起人账号 ID
     * @param authorId 被关注作者账号 ID
     * @return 关注生效事件载荷
     */
    public static AuthorActionPayload follow(String userId, String authorId) {
        return new AuthorActionPayload(userId, authorId, ACTION_FOLLOW, STATE_ACTIVE);
    }

    /**
     * 构造取消关注载荷。
     *
     * @param userId 取关发起人账号 ID
     * @param authorId 被取关作者账号 ID
     * @return 取消关注事件载荷
     */
    public static AuthorActionPayload unfollow(String userId, String authorId) {
        return new AuthorActionPayload(userId, authorId, ACTION_FOLLOW, STATE_INACTIVE);
    }
}
