package com.calles.platform.user.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.user.application.follow.UserFollowApplicationService;
import com.calles.platform.user.application.security.UserAccessPolicy;
import com.calles.platform.user.interfaces.http.dto.FollowResponses;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户关注与粉丝 HTTP 控制器，挂载于 {@code /api/users/**}。
 * 支撑关注/取关操作、社交关系判定、关注与粉丝分页展示及关系统计快照查询。
 */
@RestController
@RequestMapping("/api/users")
public class UserFollowController {

    private final UserFollowApplicationService followService;
    private final UserAccessPolicy accessPolicy;

    public UserFollowController(UserFollowApplicationService followService, UserAccessPolicy accessPolicy) {
        this.followService = followService;
        this.accessPolicy = accessPolicy;
    }

    /**
     * 关注指定目标用户。
     *
     * @param targetUserId 被关注的目标创作者账号ID
     * @return 关注结果与互关判定
     */
    @PostMapping("/{targetUserId}/follow")
    public ApiResponse<FollowResponses.Action> follow(@PathVariable String targetUserId) {
        UserInfo user = accessPolicy.requireUser();
        return ApiResponse.ok(followService.follow(user.userId(), targetUserId));
    }

    /**
     * 取消关注指定目标用户。
     *
     * @param targetUserId 被取消关注的目标创作者账号ID
     * @return 操作结果
     */
    @DeleteMapping("/{targetUserId}/follow")
    public ApiResponse<FollowResponses.Action> unfollow(@PathVariable String targetUserId) {
        UserInfo user = accessPolicy.requireUser();
        return ApiResponse.ok(followService.unfollow(user.userId(), targetUserId));
    }

    /**
     * 查询当前登录用户与指定目标用户之间的社交关系（单向/双向/互相关注）。
     *
     * @param targetUserId 目标用户账号ID
     * @return 双方关系枚举
     */
    @GetMapping("/{targetUserId}/relation")
    public ApiResponse<FollowResponses.Relation> getRelation(@PathVariable String targetUserId) {
        // 支持登录与匿名查看（未登录返回 NONE）
        String currentUserId = UserContext.get().map(UserInfo::userId).orElse(null);
        return ApiResponse.ok(followService.getRelation(currentUserId, targetUserId));
    }

    /**
     * 分页查询指定用户的关注列表（按关注时间倒序）。
     *
     * @param accountId 目标用户账号ID
     * @param page 页码（默认 1）
     * @param size 每页条数（默认 20）
     * @return 分页关注数据与公开名片
     */
    @GetMapping("/{accountId}/following")
    public ApiResponse<FollowResponses.Page> getFollowing(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        accessPolicy.requireAuthenticated();
        String currentUserId = UserContext.get().map(UserInfo::userId).orElse(null);
        return ApiResponse.ok(followService.getFollowingList(accountId, currentUserId, page, size));
    }

    /**
     * 分页查询指定用户的粉丝列表（按成为粉丝时间倒序）。
     *
     * @param accountId 目标用户账号ID
     * @param page 页码（默认 1）
     * @param size 每页条数（默认 20）
     * @return 分页粉丝数据与公开名片
     */
    @GetMapping("/{accountId}/followers")
    public ApiResponse<FollowResponses.Page> getFollowers(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        accessPolicy.requireAuthenticated();
        String currentUserId = UserContext.get().map(UserInfo::userId).orElse(null);
        return ApiResponse.ok(followService.getFollowersList(accountId, currentUserId, page, size));
    }

    /**
     * 查询指定用户的关注与粉丝统计数据。
     *
     * @param accountId 目标用户账号ID
     * @return 关注数与粉丝数实体
     */
    @GetMapping("/{accountId}/stats")
    public ApiResponse<FollowResponses.Stats> getStats(@PathVariable String accountId) {
        accessPolicy.requireAuthenticated();
        return ApiResponse.ok(followService.getStats(accountId));
    }
}
