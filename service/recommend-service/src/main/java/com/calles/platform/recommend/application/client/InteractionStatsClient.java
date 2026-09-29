package com.calles.platform.recommend.application.client;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.recommend.application.client.dto.FollowingVideoStat;
import com.calles.platform.recommend.application.client.dto.FollowingVideoStatsRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 互动公开视频统计客户端。
 *
 * <p>只读取互动服务已汇总的公开播放量，不访问互动表或复制计数。</p>
 */
@FeignClient(name = "interaction-service", contextId = "interactionStatsClient", configuration = FollowingFeignConfiguration.class)
public interface InteractionStatsClient {

    /**
     * 批量查询候选视频公开统计。
     *
     * @param request 视频短码请求
     * @return vid 到公开统计 DTO 的映射
     */
    @PostMapping("/api/interactions/videos/stats")
    ApiResponse<Map<String, FollowingVideoStat>> getVideoStats(@RequestBody FollowingVideoStatsRequest request);
}
