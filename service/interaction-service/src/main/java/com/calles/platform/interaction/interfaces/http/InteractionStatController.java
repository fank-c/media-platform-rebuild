package com.calles.platform.interaction.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.application.query.InteractionQueryApplicationService;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.interfaces.http.dto.InteractionRequests;
import com.calles.platform.interaction.interfaces.http.dto.InteractionResponses;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频互动统计与快照查询 HTTP 控制器。
 *
 * <p>挂载于 {@code /api/interactions}，负责播放页聚合状态快照、公开统计与批量计数装配。</p>
 */
@RestController
@RequestMapping("/api/interactions")
@RequiredArgsConstructor
public class InteractionStatController {

    private final InteractionQueryApplicationService queryService;
    private final InteractionAccessPolicy accessPolicy;

    /**
     * 获取当前登录用户针对特定视频的互动快照（播放页一站式聚合）。
     *
     * @param vid 视频业务公开短码
     * @return 聚合状态
     */
    @GetMapping("/videos/{vid}/my-state")
    public ApiResponse<InteractionResponses.MyState> getMyState(@PathVariable String vid) {
        String userId = accessPolicy.getCurrentUser().map(UserInfo::userId).orElse(null);
        InteractionQueryApplicationService.UserInteractionState state = queryService.getMyState(vid, userId);
        return ApiResponse.ok(new InteractionResponses.MyState(
                state.getVid(),
                state.isLiked(),
                state.isStarred(),
                state.getLastWatchPosition(),
                state.isCompleted()
        ));
    }

    /**
     * 获取单条视频的公开互动统计。
     *
     * @param vid 视频编码
     * @return 互动统计计数响应
     */
    @GetMapping("/videos/{vid}/stat")
    public ApiResponse<InteractionResponses.VideoStat> getVideoStat(@PathVariable String vid) {
        VideoCounter counter = queryService.getVideoStat(vid);
        return ApiResponse.ok(new InteractionResponses.VideoStat(
                counter.getVid(),
                counter.getViewCount(),
                counter.getLikeCount(),
                counter.getStarCount(),
                counter.getShareCount(),
                counter.getCommentCount()
        ));
    }

    /**
     * 批量查询多个视频的公开互动统计列表。
     *
     * @param request 包含视频编码集合的请求体
     * @return 视频统计映射响应
     */
    @PostMapping("/videos/stats")
    public ApiResponse<Map<String, InteractionResponses.VideoStat>> getBatchVideoStats(
            @RequestBody(required = false) InteractionRequests.BatchStats request) {
        List<String> vids = request != null && request.vids() != null ? request.vids() : List.of();
        Map<String, VideoCounter> map = queryService.getBatchVideoStats(vids);
        Map<String, InteractionResponses.VideoStat> result = map.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new InteractionResponses.VideoStat(
                                e.getValue().getVid(),
                                e.getValue().getViewCount(),
                                e.getValue().getLikeCount(),
                                e.getValue().getStarCount(),
                                e.getValue().getShareCount(),
                                e.getValue().getCommentCount()
                        )
                ));
        return ApiResponse.ok(result);
    }

    /**
     * 记录并自增视频分享计数。
     *
     * @param vid 视频编码
     * @return 动作响应
     */
    @PostMapping("/videos/{vid}/share")
    public ApiResponse<InteractionResponses.ActionResult> share(@PathVariable String vid) {
        String userId = accessPolicy.getCurrentUser().map(UserInfo::userId).orElse("anonymous");
        queryService.recordShare(vid, userId);
        return ApiResponse.ok(new InteractionResponses.ActionResult(vid, "SHARE", true));
    }
}
