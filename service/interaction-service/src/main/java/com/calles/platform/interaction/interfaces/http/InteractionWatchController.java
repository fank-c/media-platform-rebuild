package com.calles.platform.interaction.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import com.calles.platform.interaction.application.watch.WatchHeartbeatApplicationService;
import com.calles.platform.interaction.application.watch.WatchHeartbeatCommand;
import com.calles.platform.interaction.application.watch.WatchHeartbeatOutcome;
import com.calles.platform.interaction.application.watch.WatchHistoryEntryView;
import com.calles.platform.interaction.application.watch.WatchProgressApplicationService;
import com.calles.platform.interaction.application.watch.WatchProgressView;
import com.calles.platform.interaction.interfaces.http.dto.InteractionRequests;
import com.calles.platform.interaction.interfaces.http.dto.InteractionResponses;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频观看历史与播放心跳 HTTP 控制器。
 *
 * <p>挂载于 {@code /api/interactions}，提供播放心跳上报与观看历史查询、删除。</p>
 */
@RestController
@RequestMapping("/api/interactions")
@RequiredArgsConstructor
public class InteractionWatchController {

    private final WatchHeartbeatApplicationService watchHeartbeatService;
    private final WatchProgressApplicationService watchProgressService;
    private final InteractionAccessPolicy accessPolicy;

    /**
     * 上报视频播放心跳。仅接受已登录用户，未登录时不写入观看状态或播放量。
     *
     * <p>区分起播请求（sessionId 为空，sequence=0，deltaDuration=0，Header Idempotency-Key 必填）
     * 与后续心跳请求（sessionId 必填，sequence 严格递增）。
     * 视频时长不通过请求体上报：服务端只使用 content-service 事件建立的本地快照。</p>
     *
     * @param vid 视频业务公开短码
     * @param idempotencyKey 起播请求幂等键 (来自 Header Idempotency-Key，起播必填)
     * @param request 心跳数据体 (会话 ID、序号、位置、增量时长)
     * @return 心跳结果，包含会话标识与服务端判定状态
     */
    @PostMapping("/videos/{vid}/heartbeat")
    public ApiResponse<InteractionResponses.WatchHeartbeat> heartbeat(
            @PathVariable String vid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) InteractionRequests.Heartbeat request) {
        // 步骤 1：先校验登录身份，再处理心跳，避免游客写入观看状态或播放量。
        UserInfo user = accessPolicy.requireUser();

        // 步骤 2：请求体缺失直接返回 400，不再把任意空请求当成有效起播。
        if (request == null) {
            throw new IllegalArgumentException("心跳请求体不能为空");
        }

        String sessionId = request.sessionId();
        Long sequence = request.sequence();
        int position = request.position();
        Integer deltaDuration = request.deltaDuration();

        // 步骤 3：区分起播请求与后续心跳请求，严格收紧参数校验。
        boolean isStart = (sessionId == null || sessionId.isBlank());
        if (isStart) {
            // 起播：Header Idempotency-Key 必填且最多 64 字符；sequence 为 0；deltaDuration 为 0
            if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.trim().length() > 64) {
                throw new IllegalArgumentException("起播请求必须在 Header 中携带有效的 Idempotency-Key (不超过64字符)");
            }
            if (sequence != null && sequence != 0L) {
                throw new IllegalArgumentException("起播请求心跳序号必须为 0");
            }
            if (deltaDuration != null && deltaDuration != 0) {
                throw new IllegalArgumentException("起播请求增量时长必须为 0");
            }
            if (position < 0) {
                throw new IllegalArgumentException("播放位置不能为负数");
            }
        } else {
            // 后续心跳：sequence 必须为正整数；position 与 deltaDuration 不能为负数
            if (sequence == null || sequence <= 0L) {
                throw new IllegalArgumentException("心跳序号必须为正整数");
            }
            if (position < 0) {
                throw new IllegalArgumentException("播放位置不能为负数");
            }
            if (deltaDuration != null && deltaDuration < 0) {
                throw new IllegalArgumentException("心跳增量时长不能为负数");
            }
        }

        WatchHeartbeatCommand command = new WatchHeartbeatCommand(
                isStart ? null : sessionId.trim(),
                isStart ? 0L : sequence,
                position,
                isStart ? 0 : (deltaDuration != null ? deltaDuration : 0),
                isStart ? idempotencyKey.trim() : null
        );

        WatchHeartbeatOutcome outcome = watchHeartbeatService.processHeartbeat(vid, user.userId(), command);
        return ApiResponse.ok(new InteractionResponses.WatchHeartbeat(
                outcome.vid(),
                outcome.sessionId(),
                outcome.acceptedSequence(),
                outcome.lastPosition(),
                outcome.watchedDuration(),
                outcome.sessionWatchedDuration(),
                outcome.videoDuration(),
                outcome.qualificationThreshold(),
                outcome.qualifiedThisSession(),
                outcome.viewCountedThisSession(),
                outcome.completedThisSession(),
                outcome.duplicateOrStaleRequest()
        ));
    }

    /**
     * 获取指定视频的断点续播进度；游客返回零进度，不读取任何共享断点。
     *
     * @param vid 视频编码
     * @return 断点进度数据
     */
    @GetMapping("/videos/{vid}/watch-progress")
    public ApiResponse<InteractionResponses.WatchProgress> getProgress(@PathVariable String vid) {
        // 步骤 1：仅登录用户查询个人进度，游客一律返回零进度。
        Optional<UserInfo> currentUser = accessPolicy.getCurrentUser();
        if (currentUser.isEmpty()) {
            return ApiResponse.ok(new InteractionResponses.WatchProgress(vid, 0, 0, 0, false));
        }
        return ApiResponse.ok(toResponse(watchProgressService.getProgress(vid, currentUser.get().userId())));
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
        List<WatchHistoryEntryView> list = watchProgressService.getHistoryPage(user.userId(), page, size);
        List<InteractionResponses.WatchHistoryItem> dtos = list.stream()
                .map(entry -> new InteractionResponses.WatchHistoryItem(
                        entry.id(),
                        entry.vid(),
                        entry.lastPosition(),
                        entry.watchedDuration(),
                        entry.videoDuration(),
                        entry.completed(),
                        entry.firstWatchAt(),
                        entry.lastWatchAt()
                ))
                .toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * 删除单条或清空全部观看历史（只隐藏展示，不释放播放量冷却与事件防重状态）。
     *
     * @param vid 视频编码 (可选，若为空则清空全部)
     * @return 成功状态
     */
    @DeleteMapping("/watch/history")
    public ApiResponse<Void> deleteHistory(@RequestParam(required = false) String vid) {
        UserInfo user = accessPolicy.requireUser();
        if (vid != null && !vid.isBlank()) {
            watchProgressService.removeHistory(vid.trim(), user.userId());
        } else {
            watchProgressService.clearAllHistory(user.userId());
        }
        return ApiResponse.ok();
    }

    /**
     * 把进度视图映射为断点进度响应体。
     *
     * @param view 进度视图
     * @return 断点进度响应体
     */
    private InteractionResponses.WatchProgress toResponse(WatchProgressView view) {
        return new InteractionResponses.WatchProgress(
                view.vid(), view.lastPosition(), view.watchedDuration(), view.videoDuration(), view.completed());
    }
}
