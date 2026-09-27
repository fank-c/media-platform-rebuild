package com.calles.platform.interaction.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.application.like.LikeApplicationService;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import com.calles.platform.interaction.domain.model.like.VideoLike;
import com.calles.platform.interaction.interfaces.http.dto.InteractionResponses;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频点赞 HTTP 控制器。
 *
 * <p>提供点赞、取消点赞以及本人点赞列表分页查询，统一挂载于 {@code /api/interactions}。</p>
 */
@RestController
@RequestMapping("/api/interactions")
@RequiredArgsConstructor
public class InteractionLikeController {

    private final LikeApplicationService likeService;
    private final InteractionAccessPolicy accessPolicy;

    /**
     * 点赞指定视频。
     *
     * @param vid 视频公开短码
     * @return 点赞结果
     */
    @PostMapping("/videos/{vid}/like")
    public ApiResponse<InteractionResponses.ActionResult> like(@PathVariable String vid) {
        UserInfo user = accessPolicy.requireUser();
        boolean active = likeService.likeVideo(vid, user.userId());
        return ApiResponse.ok(new InteractionResponses.ActionResult(vid, "LIKE", active));
    }

    /**
     * 取消点赞指定视频。
     *
     * @param vid 视频公开短码
     * @return 操作结果
     */
    @DeleteMapping("/videos/{vid}/like")
    public ApiResponse<InteractionResponses.ActionResult> unlike(@PathVariable String vid) {
        UserInfo user = accessPolicy.requireUser();
        boolean active = likeService.unlikeVideo(vid, user.userId());
        return ApiResponse.ok(new InteractionResponses.ActionResult(vid, "UNLIKE", active));
    }

    /**
     * 分页查询当前登录用户有效点赞的视频记录。
     *
     * @param page 页码（从 1 起始，默认 1）
     * @param size 每页大小（默认 20，限制 1~100）
     * @return 点赞明细项列表
     */
    @GetMapping("/likes")
    public ApiResponse<List<InteractionResponses.LikeVideoItem>> getLikedVideos(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserInfo user = accessPolicy.requireUser();
        List<VideoLike> likes = likeService.getLikedVideos(user.userId(), page, size);
        List<InteractionResponses.LikeVideoItem> dtos = likes.stream()
                .map(like -> new InteractionResponses.LikeVideoItem(like.getId(), like.getVid(), like.getCreatedAt()))
                .toList();
        return ApiResponse.ok(dtos);
    }
}
