package com.calles.platform.content.interfaces.http.video;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserInfo;
import com.calles.platform.content.application.security.ContentAccessPolicy;
import com.calles.platform.content.application.video.VideoPublishApplicationService;
import com.calles.platform.content.application.video.VideoQueryApplicationService;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 创作者端 HTTP API 控制器。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：专属于创作者（Author / Creator）业务流程的接口适配层；</li>
 *   <li><b>核心用例</b>：草稿创建、图文元数据编辑、提交审核、主动下架、作品物理/逻辑删除以及个人工作台作品分页检索；</li>
 *   <li><b>鉴权要求</b>：所有接口均强制校验登录状态与创作者角色（{@code requireUser()}），防止未登录匿名调用或越权访问；</li>
 *   <li><b>不应承担的工作</b>：不处理前台播放流、不处理平台管理风控治理、不处理外部微服务异步回调。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/content/videos")
@RequiredArgsConstructor
public class CreatorVideoController {

    /** 视频创作与发布流转应用服务。 */
    private final VideoPublishApplicationService publishService;

    /** 视频读模型多维度检索应用服务。 */
    private final VideoQueryApplicationService queryService;

    /** 统一认证上下文与权限门禁策略。 */
    private final ContentAccessPolicy accessPolicy;

    /** 视频流水线任务协调器。 */
    private final com.calles.platform.content.application.task.VideoTaskCoordinator videoTaskCoordinator;

    /**
     * 创作者创建视频草稿。
     *
     * @param request 创建视频草稿请求体（包含标题、简介、源视频文件ID、封面文件ID、时长与初始标签等）
     * @return 包含新生成业务短码 {@code vid} 的标准响应实体（HTTP 201 Created）
     */
    @PostMapping("/draft")
    public ResponseEntity<ApiResponse<Map<String, String>>> createDraft(
            @Valid @RequestBody VideoRequests.CreateDraft request) {
        // 步骤 1：校验调用主体必须携带有效用户认证身份
        UserInfo user = accessPolicy.requireUser();
        // 步骤 2：调用发布服务持久化草稿聚合根并关联打标
        String vid = publishService.createDraft(user.userId(), request);
        // 步骤 3：返回 201 Created 状态与 24 位业务编码 vid
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(Map.of("vid", vid)));
    }

    /**
     * 创作者更新视频图文元数据（标题、简介、封面图、标签）。
     *
     * @param id 视频内部全局主键 ID
     * @param request 元数据更新请求体
     * @return 标准成功响应
     */
    @PutMapping("/{id}")
    public ApiResponse<Void> updateMetadata(
            @PathVariable String id,
            @Valid @RequestBody VideoRequests.UpdateMetadata request) {
        // 步骤 1：验证操作人身份并确保为作者本人或管理员
        UserInfo user = accessPolicy.requireUser();
        // 步骤 2：执行元数据更新与标签字典全量重新对齐
        publishService.updateMetadata(user.userId(), id, request);
        return ApiResponse.ok();
    }

    /**
     * 创作者提交视频进入平台审核流水线。
     *
     * @param id 视频内部全局主键 ID
     * @return 标准成功响应
     */
    @PostMapping("/{id}/submit")
    public ApiResponse<Void> submitForAudit(@PathVariable String id) {
        // 步骤 1：提取当前登录用户上下文
        UserInfo user = accessPolicy.requireUser();
        // 步骤 2：调用下游 file-service 验证视频与封面就绪状态，流转为 AUDITING 并写入 Outbox
        publishService.submitForAudit(user.userId(), id);
        return ApiResponse.ok();
    }

    /**
     * 创作者主动下架视频。
     *
     * @param id 视频内部全局主键 ID
     * @param request 下架说明请求体（选填）
     * @return 标准成功响应
     */
    @PostMapping("/{id}/offline")
    public ApiResponse<Void> takeOffline(
            @PathVariable String id,
            @RequestBody(required = false) VideoRequests.Offline request) {
        // 步骤 1：提取登录创作者身份
        UserInfo user = accessPolicy.requireUser();
        String reason = request != null ? request.reason() : null;
        // 步骤 2：流转生命周期为 OFFLINE 并发送领域事件下线索引
        publishService.takeOffline(user.userId(), id, reason);
        return ApiResponse.ok();
    }

    /**
     * 创作者删除草稿或已下架视频。
     *
     * @param id 视频内部全局主键 ID
     * @return 204 No Content 响应实体
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVideo(@PathVariable String id) {
        // 步骤 1：校验必须持有创作者登录身份
        UserInfo user = accessPolicy.requireUser();
        // 步骤 2：执行逻辑删除并级联清理标签热度
        publishService.deleteVideo(user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 创作者工作台查询本人视频作品分页列表。
     *
     * @param publishStatus 发布状态过滤条件（选填，如 DRAFT, AUDITING 等）
     * @param page 页码（从 1 起始，默认 1）
     * @param size 每页大小（默认 20，上限 100）
     * @return 创作者分页列表响应
     */
    @GetMapping("/me")
    public ApiResponse<VideoResponses.CreatorPage> listMyVideos(
            @RequestParam(required = false) String publishStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 步骤 1：获取当前创作者的账户 ID
        UserInfo user = accessPolicy.requireUser();
        // 步骤 2：执行按创建时间倒序的分页查询
        return ApiResponse.ok(queryService.listMyVideos(user.userId(), publishStatus, page, size));
    }

    /**
     * 创作者工作台查询指定视频的发布流水线与细分子任务执行进度。
     *
     * @param id 视频内部全局主键 ID
     * @return 流水线全景进度响应
     */
    @GetMapping("/{id}/tasks")
    public ApiResponse<VideoResponses.PipelineProgress> getPipelineProgress(@PathVariable String id) {
        // 步骤 1：校验调用主体必须持有有效创作者或管理员登录身份
        UserInfo user = accessPolicy.requireUser();
        // 步骤 2：定位视频并校验仅作者本人与管理员有权观测流水线任务状态
        VideoContent video = queryService.findVideoOrThrow(id);
        accessPolicy.requireOwnerOrAdmin(video.getAuthorId());
        // 步骤 3：返回各子任务的执行进度与门禁就绪指标
        return ApiResponse.ok(videoTaskCoordinator.getPipelineProgress(id));
    }
}

