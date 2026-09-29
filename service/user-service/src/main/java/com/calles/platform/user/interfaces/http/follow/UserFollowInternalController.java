package com.calles.platform.user.interfaces.http.follow;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.user.application.follow.UserFollowApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户关注内部服务间调用控制器，专供微服务集群内部协同（如 recommend-service 关注流作品召回）。
 * 严禁暴露至公网网关外部路由。
 */
@RestController
@RequestMapping("/api/users/internal")
public class UserFollowInternalController {

    private final UserFollowApplicationService followService;

    public UserFollowInternalController(UserFollowApplicationService followService) {
        this.followService = followService;
    }

    /**
     * 提取指定用户最近关注的有界作者账号 ID 清单（用于关注召回通道）。
     *
     * @param accountId 用户账号 ID
     * @return 最多 1000 个有效关注作者 ID
     */
    @GetMapping("/{accountId}/following-ids")
    public ApiResponse<List<String>> getFollowingIds(@PathVariable String accountId) {
        return ApiResponse.ok(followService.getRecentFollowingIds(accountId));
    }
}
