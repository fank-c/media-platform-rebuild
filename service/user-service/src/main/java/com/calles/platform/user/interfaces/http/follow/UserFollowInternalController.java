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
     * 全量提取指定用户关注的博主账号ID清单（用于个性化关注召回通道）。
     *
     * @param accountId 用户账号ID
     * @return 关注的创作者ID列表
     */
    @GetMapping("/{accountId}/following-ids")
    public ApiResponse<List<String>> getFollowingIds(@PathVariable String accountId) {
        return ApiResponse.ok(followService.getAllFollowingIds(accountId));
    }
}
