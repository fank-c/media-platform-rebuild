package com.calles.platform.interaction.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import com.calles.platform.interaction.application.watch.WatchHeartbeatApplicationService;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.interfaces.http.dto.InteractionRequests;
import com.calles.platform.interaction.interfaces.http.dto.InteractionResponses;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频观看历史与播放心跳 HTTP 控制器。
 *
 * <p>挂载于 {@code /api/interactions}，接收播放端断点心跳上报并提供播放历史查询与清理。</p>
 */
@RestController
@RequestMapping("/api/interactions")
@RequiredArgsConstructor
public class InteractionWatchController {

    private final WatchHeartbeatApplicationService watchService;
    private final InteractionAccessPolicy accessPolicy;

    /**
     * 上报视频播放心跳与断点。
     *
     * @param vid 视频业务公开短码
     * @param request 心跳数据体 (当前位置、时段增量、总时长)
     * @return 确认响应
     */
    @PostMapping("/videos/{vid}/heartbeat")
    public ApiResponse<InteractionResponses.WatchProgress> heartbeat(
            @PathVariable String vid,
            @RequestBody InteractionRequests.Heartbeat request) {
        // 提取用户身份；若当前未登录则使用匿名会话标示
        String userId = accessPolicy.getCurrentUser().map(UserInfo::userId).orElse("anonymous");

        WatchHistory history = watchService.processHeartbeat(
                vid,
                userId,
                request != null ? request.position() : 0,
                request != null ? request.deltaDuration() : 0,
                request != null ? request.videoDuration() : 0
        );

        return ApiResponse.ok(new InteractionResponses.WatchProgress(
                history.getVid(),
                history.getLastPosition(),
                history.getWatchedDuration(),
                history.getVideoDuration(),
                history.isCompleted()
        ));
    }

    /**
     * 获取指定视频的断点续播进度。
     *
     * @param vid 视频编码
     * @return 断点进度数据
     */
    @GetMapping("/videos/{vid}/watch-progress")
    public ApiResponse<InteractionResponses.WatchProgress> getProgress(@PathVariable String vid) {
        String userId = accessPolicy.getCurrentUser().map(UserInfo::userId).orElse("anonymous");
        return ApiResponse.ok(watchService.getWatchProgress(vid, userId)
                .map(h -> new InteractionResponses.WatchProgress(
                        h.getVid(), h.getLastPosition(), h.getWatchedDuration(), h.getVideoDuration(), h.isCompleted()))
                .orElseGet(() -> new InteractionResponses.WatchProgress(vid, 0, 0, 0, false)));
    }

    /**
     * 分页查询当前登录用户的观看历史。
     *
     * @param page 页码 (默认 1)
     * @param size 每页大小 (默认 20)
     * @return 历史记录列表
     */
    @GetMapping("/watch/history")
    public ApiResponse<List<InteractionResponses.WatchHistoryItem>> getHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserInfo user = accessPolicy.requireUser();
        List<WatchHistory> list = watchService.getHistoryPage(user.userId(), page, size);
        List<InteractionResponses.WatchHistoryItem> dtos = list.stream()
                .map(h -> new InteractionResponses.WatchHistoryItem(
                        h.getId(),
                        h.getVid(),
                        h.getLastPosition(),
                        h.getWatchedDuration(),
                        h.getVideoDuration(),
                        h.isCompleted(),
                        h.getFirstWatchAt(),
                        h.getLastWatchAt()
                ))
                .toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * 删除单条或清空全部观看历史。
     *
     * @param vid 视频编码 (可选，若为空则清空全部)
     * @return 成功状态
     */
    @DeleteMapping("/watch/history")
    public ApiResponse<Void> deleteHistory(@RequestParam(required = false) String vid) {
        UserInfo user = accessPolicy.requireUser();
        if (vid != null && !vid.isBlank()) {
            watchService.removeHistory(vid.trim(), user.userId());
        } else {
            watchService.clearAllHistory(user.userId());
        }
        return ApiResponse.ok();
    }
}
