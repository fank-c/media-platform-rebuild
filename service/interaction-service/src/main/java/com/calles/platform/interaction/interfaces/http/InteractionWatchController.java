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
     * 用户点进视频发起起播（记录历史、判定防刷冷却累加播放量，并返回续播断点）。
     *
     * @param vid 视频业务公开短码
     * @return 包含当前续播断点秒数的 WatchProgress 响应
     */
    @PostMapping("/videos/{vid}/play")
    public ApiResponse<InteractionResponses.WatchProgress> play(@PathVariable String vid) {
        UserInfo user = accessPolicy.requireUser();
        WatchHistory history = watchService.startPlay(vid, user.userId());
        return ApiResponse.ok(new InteractionResponses.WatchProgress(
                history.getVid(),
                history.getLastPosition(),
                history.getWatchedDuration(),
                history.getVideoDuration(),
                history.isCompleted()
        ));
    }

    /**
     * 仅接受已登录用户上报的视频播放心跳与断点，未登录时不写入历史或计数。
     *
     * @param vid 视频业务公开短码
     * @param request 心跳数据体 (当前位置、时段增量、总时长)
     * @return 确认响应
     */
    @PostMapping("/videos/{vid}/heartbeat")
    public ApiResponse<InteractionResponses.WatchProgress> heartbeat(
            @PathVariable String vid,
            @RequestBody InteractionRequests.Heartbeat request) {
        // 步骤 1：先校验登录身份，再处理心跳，避免游客写入观看历史或播放量。
        UserInfo user = accessPolicy.requireUser();

        WatchHistory history = watchService.processHeartbeat(
                vid,
                user.userId(),
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
     * 获取指定视频的断点续播进度；游客返回零进度，不读取旧的匿名历史。
     *
     * @param vid 视频编码
     * @return 断点进度数据
     */
    @GetMapping("/videos/{vid}/watch-progress")
    public ApiResponse<InteractionResponses.WatchProgress> getProgress(@PathVariable String vid) {
        // 步骤 1：仅登录用户查询个人历史，游客不共享 anonymous 断点。
        return ApiResponse.ok(accessPolicy.getCurrentUser()
                .flatMap(user -> watchService.getWatchProgress(vid, user.userId()))
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
