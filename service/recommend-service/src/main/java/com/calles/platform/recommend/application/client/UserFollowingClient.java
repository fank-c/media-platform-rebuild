package com.calles.platform.recommend.application.client;

import com.calles.platform.common.core.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * 用户关注关系内部查询客户端。
 *
 * <p>仅获取有界的关注作者 ID，不复制用户服务的关系数据。</p>
 */
@FeignClient(name = "user-service", contextId = "userFollowingClient", configuration = FollowingFeignConfiguration.class)
public interface UserFollowingClient {

    /**
     * 查询用户最近关注的有效作者集合。
     *
     * @param accountId 已验证的当前用户账号 ID
     * @return 最多 1000 个作者 ID 的统一响应
     */
    @GetMapping("/api/users/internal/{accountId}/following-ids")
    ApiResponse<List<String>> getRecentFollowingIds(@PathVariable("accountId") String accountId);
}
