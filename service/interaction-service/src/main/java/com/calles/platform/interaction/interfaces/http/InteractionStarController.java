package com.calles.platform.interaction.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.interaction.application.security.InteractionAccessPolicy;
import com.calles.platform.interaction.application.star.StarApplicationService;
import com.calles.platform.interaction.domain.model.star.StarFolder;
import com.calles.platform.interaction.domain.model.star.StarItem;
import com.calles.platform.interaction.interfaces.http.dto.InteractionRequests;
import com.calles.platform.interaction.interfaces.http.dto.InteractionResponses;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 视频收藏与收藏夹 HTTP 控制器。
 *
 * <p>挂载于 {@code /api/interactions}，负责视频收藏/移除及收藏夹管理。</p>
 */
@RestController
@RequestMapping("/api/interactions")
@RequiredArgsConstructor
public class InteractionStarController {

    private final StarApplicationService starService;
    private final InteractionAccessPolicy accessPolicy;

    /**
     * 收藏指定视频（支持指定收藏夹，若未指定则归入默认收藏夹）。
     *
     * @param vid 视频编码
     * @param request 收藏请求体 (可选 folderId)
     * @return 收藏明细标识
     */
    @PostMapping("/videos/{vid}/star")
    public ApiResponse<InteractionResponses.ActionResult> star(
            @PathVariable String vid,
            @RequestBody(required = false) InteractionRequests.Star request) {
        UserInfo user = accessPolicy.requireUser();
        String folderId = request != null ? request.folderId() : null;
        starService.starVideo(vid, user.userId(), folderId);
        return ApiResponse.ok(new InteractionResponses.ActionResult(vid, "STAR", true));
    }

    /**
     * 取消收藏视频（支持指定收藏夹，若未指定则全局移出）。
     *
     * @param vid 视频编码
     * @param folderId 收藏夹 ID (可选)
     * @return 操作结果
     */
    @DeleteMapping("/videos/{vid}/star")
    public ApiResponse<InteractionResponses.ActionResult> unstar(
            @PathVariable String vid,
            @RequestParam(required = false) String folderId) {
        UserInfo user = accessPolicy.requireUser();
        starService.unstarVideo(vid, user.userId(), folderId);
        return ApiResponse.ok(new InteractionResponses.ActionResult(vid, "UNSTAR", false));
    }

    /**
     * 获取当前登录用户的所有可用收藏夹列表。
     *
     * @return 收藏夹列表
     */
    @GetMapping("/star/folders")
    public ApiResponse<List<InteractionResponses.StarFolderItem>> getFolders() {
        UserInfo user = accessPolicy.requireUser();
        List<StarFolder> folders = starService.getUserFolders(user.userId());
        List<InteractionResponses.StarFolderItem> dtos = folders.stream()
                .map(f -> new InteractionResponses.StarFolderItem(
                        f.getId(), f.getTitle(), f.isDefault(), f.getStatus(), f.getCreatedAt()))
                .toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * 新建用户自定义收藏夹。
     *
     * @param request 包含标题的建夹请求
     * @return 新建收藏夹信息
     */
    @PostMapping("/star/folders")
    public ApiResponse<InteractionResponses.StarFolderItem> createFolder(
            @Valid @RequestBody InteractionRequests.CreateFolder request) {
        UserInfo user = accessPolicy.requireUser();
        StarFolder folder = starService.createCustomFolder(user.userId(), request.title());
        return ApiResponse.ok(new InteractionResponses.StarFolderItem(
                folder.getId(), folder.getTitle(), folder.isDefault(), folder.getStatus(), folder.getCreatedAt()));
    }

    /**
     * 修改用户自定义收藏夹标题。
     *
     * @param folderId 待更名的收藏夹 ID
     * @param request 包含新标题的更名请求体
     * @return 修改后的收藏夹信息
     */
    @PutMapping("/star/folders/{folderId}")
    public ApiResponse<InteractionResponses.StarFolderItem> updateFolder(
            @PathVariable String folderId,
            @Valid @RequestBody InteractionRequests.UpdateFolder request) {
        UserInfo user = accessPolicy.requireUser();
        StarFolder folder = starService.renameFolder(folderId, user.userId(), request.title());
        return ApiResponse.ok(new InteractionResponses.StarFolderItem(
                folder.getId(), folder.getTitle(), folder.isDefault(), folder.getStatus(), folder.getCreatedAt()));
    }

    /**
     * 删除用户自定义收藏夹（级联清理收藏明细并联动计数）。
     *
     * @param folderId 待删除的收藏夹 ID
     * @return 空成功响应
     */
    @DeleteMapping("/star/folders/{folderId}")
    public ApiResponse<Void> deleteFolder(@PathVariable String folderId) {
        UserInfo user = accessPolicy.requireUser();
        starService.deleteFolder(folderId, user.userId());
        return ApiResponse.ok(null);
    }

    /**
     * 分页查询指定收藏夹内的视频明细。
     *
     * @param folderId 收藏夹 ID (可选，为空时查默认收藏夹)
     * @param page 页码 (从 1 起始)
     * @param size 每页大小
     * @return 收藏条目列表
     */
    @GetMapping("/star/items")
    public ApiResponse<List<InteractionResponses.StarVideoItem>> getStarItems(
            @RequestParam(required = false) String folderId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UserInfo user = accessPolicy.requireUser();
        List<StarItem> items = starService.getStarItems(folderId, user.userId(), page, size);
        List<InteractionResponses.StarVideoItem> dtos = items.stream()
                .map(i -> new InteractionResponses.StarVideoItem(i.getId(), i.getFolderId(), i.getVid(), i.getCreatedAt()))
                .toList();
        return ApiResponse.ok(dtos);
    }
}
