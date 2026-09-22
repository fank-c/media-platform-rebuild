package com.calles.platform.user.interfaces.http.dto;

import com.calles.platform.user.domain.follow.FollowStatus;
import com.calles.platform.user.domain.follow.RelationType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户关注与粉丝相关的统一接口响应契约 DTO 集合。
 */
public final class FollowResponses {

    private FollowResponses() {}

    /**
     * 关注或取关操作结果。
     *
     * @param targetUserId 目标用户账号ID
     * @param followStatus 最终关注状态 (FOLLOWING / UNFOLLOWED)
     * @param mutual 是否互相关注
     */
    public record Action(String targetUserId, FollowStatus followStatus, boolean mutual) {}

    /**
     * 双方关系判定响应。
     *
     * @param targetUserId 目标用户账号ID
     * @param relation 双方社交拓扑关系类型 (NONE, FOLLOWING, FOLLOWED_BY, MUTUAL)
     */
    public record Relation(String targetUserId, RelationType relation) {}

    /**
     * 用户关系统计快照响应。
     *
     * @param accountId 用户账号ID
     * @param followingCount 关注数
     * @param followerCount 粉丝数
     */
    public record Stats(String accountId, long followingCount, long followerCount) {}

    /**
     * 关注或粉丝列表项公开资料摘要与行为时间。
     *
     * @param accountId 用户账号ID
     * @param nickname 用户公开昵称
     * @param avatarUrl 头像访问地址
     * @param bio 个人简介
     * @param followTime 关注建立或更新时间
     * @param mutual 是否互相关注
     */
    public record FollowItem(String accountId, String nickname, String avatarUrl, String bio,
                             LocalDateTime followTime, boolean mutual) {}

    /**
     * 关注或粉丝分页响应。
     *
     * @param total 总记录数
     * @param page 当前页码
     * @param size 每页容量
     * @param items 列表数据项
     */
    public record Page(long total, long page, long size, List<FollowItem> items) {}
}
