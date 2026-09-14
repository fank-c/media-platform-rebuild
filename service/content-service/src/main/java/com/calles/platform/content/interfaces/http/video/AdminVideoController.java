package com.calles.platform.content.interfaces.http.video;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.content.application.security.ContentAccessPolicy;
import com.calles.platform.content.application.video.VideoModerationApplicationService;
import com.calles.platform.content.application.video.VideoQueryApplicationService;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理后台（风控与内容治理）HTTP API 控制器。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：专属于运营与安全管理后台（Admin Console）的视频内容检索与违规治理接口适配层；</li>
 *   <li><b>核心用例</b>：管理员多条件复合分页检索视频、违规视频主动封禁（强制下架并记入风控黑名单）、误判视频解封恢复；</li>
 *   <li><b>鉴权要求</b>：所有接口均强制校验 {@code requireAdmin()}，非 ADMIN 角色一律拒绝（403 Forbidden）；</li>
 *   <li><b>不应承担的工作</b>：不处理普通创作者流程，不参与前台公开播放流组装。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/content/videos/admin")
@RequiredArgsConstructor
public class AdminVideoController {

    /** 平台风控违规治理应用服务。 */
    private final VideoModerationApplicationService moderationService;

    /** 视频多维度查询应用服务。 */
    private final VideoQueryApplicationService queryService;

    /** 统一认证上下文与权限门禁策略。 */
    private final ContentAccessPolicy accessPolicy;

    /**
     * 管理后台综合分页检索视频列表。
     *
     * @param request 组合筛选条件（状态、作者 ID、标题/vid 关键字）
     * @param page 当前页码（从 1 起始，默认 1）
     * @param size 每页大小（默认 20，上限 100）
     * @return 管理端分页列表响应
     */
    @PostMapping("/list")
    public ApiResponse<VideoResponses.AdminPage> listAdminVideos(
            @RequestBody(required = false) VideoRequests.AdminList request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 步骤 1：校验操作人必须具备 ADMIN 管理员角色
        accessPolicy.requireAdmin();
        // 步骤 2：按多条件动态组合查询全库视频
        return ApiResponse.ok(queryService.listAdminVideos(request, page, size));
    }

    /**
     * 管理后台违规封禁视频。
     *
     * @param id 视频内部主键 ID
     * @param request 封禁原因参数
     * @return 标准成功响应
     */
    @PostMapping("/{id}/ban")
    public ApiResponse<Void> banVideo(
            @PathVariable String id,
            @Valid @RequestBody VideoRequests.AdminBan request) {
        // 步骤 1：严格校验管理员权限并提取管理员 ID
        UserInfo admin = accessPolicy.requireAdmin();
        // 步骤 2：置位 status=DISABLED，写入违规下架 Outbox 领域事件
        moderationService.banVideo(admin.userId(), id, request.reason());
        return ApiResponse.ok();
    }

    /**
     * 管理后台解除视频封禁。
     *
     * @param id 视频内部主键 ID
     * @return 标准成功响应
     */
    @PostMapping("/{id}/unban")
    public ApiResponse<Void> unbanVideo(@PathVariable String id) {
        // 步骤 1：严格校验管理员权限
        UserInfo admin = accessPolicy.requireAdmin();
        // 步骤 2：恢复 status=ACTIVE，发布解封领域事件
        moderationService.unbanVideo(admin.userId(), id);
        return ApiResponse.ok();
    }
}
