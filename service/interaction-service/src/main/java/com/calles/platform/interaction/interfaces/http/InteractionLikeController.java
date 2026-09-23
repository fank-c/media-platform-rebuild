package com.calles.platform.interaction.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.application.like.LikeApplicationService;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import com.calles.platform.interaction.interfaces.http.dto.InteractionResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频点赞 HTTP 控制器。
 *
 * <p>提供点赞与取消点赞操作，统一挂载于 {@code /api/interactions/videos/{vid}/like}。</p>
 */
@RestController
@RequestMapping("/api/interactions/videos/{vid}/like")
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
    @PostMapping
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
    @DeleteMapping
    public ApiResponse<InteractionResponses.ActionResult> unlike(@PathVariable String vid) {
        UserInfo user = accessPolicy.requireUser();
        boolean active = likeService.unlikeVideo(vid, user.userId());
        return ApiResponse.ok(new InteractionResponses.ActionResult(vid, "UNLIKE", active));
    }
}
