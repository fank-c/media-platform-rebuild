package com.calles.platform.recommend.interfaces.web;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.common.web.context.UserContext;
import com.calles.platform.recommend.application.dto.RecommendFeedResult;
import com.calles.platform.recommend.application.service.FeedbackApplicationService;
import com.calles.platform.recommend.application.service.RecommendFeedApplicationService;
import com.calles.platform.recommend.application.service.UserBlockApplicationService;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.interfaces.web.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 推荐服务 HTTP 控制器 (RecommendFeedController)。
 *
 * <p>职责与端点契约：
 * <ul>
 *   <li><b>首页推荐流</b>：{@code GET /api/recommend/feed}（支持游客与登录用户，返回个性化瀑布流物料）；</li>
 *   <li><b>行为流水上报</b>：{@code POST /api/recommend/feedback}（上报播放、跳过、负反馈等事实流水并驱动画像演进）；</li>
 *   <li><b>明确拉黑屏蔽</b>：{@code POST /api/recommend/blocks}（拉黑视频/作者/主题标签）；</li>
 *   <li><b>撤销屏蔽</b>：{@code DELETE /api/recommend/blocks}；</li>
 *   <li><b>查询屏蔽列表</b>：{@code GET /api/recommend/blocks}。</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
public class RecommendFeedController {

    private final RecommendFeedApplicationService recommendFeedApplicationService;
    private final FeedbackApplicationService feedbackApplicationService;
    private final UserBlockApplicationService userBlockApplicationService;

    /**
     * 获取首页推荐瀑布流卡片列表。
     *
     * @param headerUserId 网关透传的用户账号 ID (可选)
     * @param size 请求期望获取的卡片数量 (默认 10)
     * @return 统一响应包装的推荐列表与分页标记
     */
    @GetMapping("/feed")
    public ApiResponse<RecommendFeedResponse> getFeed(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "size", defaultValue = "10") int size) {

        // 步骤 1：解析当前用户身份 (支持游客空值)
        String userId = resolveUserId(headerUserId);

        // 步骤 2：调用推荐编排应用服务执行全链路推荐
        RecommendFeedResult result = recommendFeedApplicationService.getPersonalizedFeed(userId, size);

        // 步骤 3：转换为前端网络传输 DTO
        List<RecommendItemDTO> itemDtos = result.getItems().stream()
                .map(item -> new RecommendItemDTO(
                        item.getVid(),
                        item.getChannel(),
                        item.getScore(),
                        item.getReason()
                ))
                .toList();

        return ApiResponse.ok(new RecommendFeedResponse(itemDtos, result.isHasMore()));
    }

    /**
     * 上报客户端用户交互行为流水。
     *
     * @param headerUserId 网关透传用户账号 ID (可选)
     * @param headerTraceId 客户端或网关链路追踪 ID (可选)
     * @param request 行为反馈上报载荷
     * @return 操作成功响应
     */
    @PostMapping("/feedback")
    public ApiResponse<Void> submitFeedback(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "X-Trace-Id", required = false) String headerTraceId,
            @Valid @RequestBody FeedbackSubmitRequest request) {

        // 步骤 1：解析用户与链路追踪标识
        String userId = resolveUserId(headerUserId);
        String traceId = (headerTraceId != null && !headerTraceId.isBlank())
                ? headerTraceId.trim()
                : MDC.get("traceId");

        // 步骤 2：转换行为动作类型枚举
        FeedbackActionType actionType;
        try {
            actionType = FeedbackActionType.valueOf(request.getActionType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的行为类型: " + request.getActionType());
        }

        // 步骤 3：调用应用服务记录事实日志并分流更新画像
        feedbackApplicationService.recordFeedback(
                userId,
                request.getVid(),
                actionType,
                request.getPlayDuration() != null ? request.getPlayDuration() : 0,
                request.getVideoDuration() != null ? request.getVideoDuration() : 0,
                request.getReason(),
                traceId,
                request.getOccurredAt()
        );

        return ApiResponse.ok();
    }

    /**
     * 添加明确屏蔽黑名单记录。
     *
     * @param headerUserId 网关透传用户账号 ID (必填)
     * @param request 屏蔽创建请求体
     * @return 新增的屏蔽记录信息
     */
    @PostMapping("/blocks")
    public ApiResponse<UserBlockResponse> addBlock(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @Valid @RequestBody UserBlockRequest request) {

        // 步骤 1：强校验登录态
        String userId = requireUserId(headerUserId);

        // 步骤 2：校验并解析屏蔽维度类型
        BlockType blockType;
        try {
            blockType = BlockType.valueOf(request.getBlockType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的屏蔽维度类型: " + request.getBlockType());
        }

        // 步骤 3：执行屏蔽添加
        UserBlock block = userBlockApplicationService.addBlock(
                userId,
                blockType,
                request.getTargetId(),
                request.getReason()
        );

        return ApiResponse.ok(new UserBlockResponse(
                block.getId(),
                block.getUserId(),
                block.getBlockType().name(),
                block.getTargetId(),
                block.getReason(),
                block.getCreatedAt()
        ));
    }

    /**
     * 撤销特定屏蔽记录。
     *
     * @param headerUserId 网关透传用户账号 ID (必填)
     * @param blockTypeStr 屏蔽维度类型
     * @param targetId 目标标识
     * @return 操作成功响应
     */
    @DeleteMapping("/blocks")
    public ApiResponse<Void> removeBlock(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam("blockType") String blockTypeStr,
            @RequestParam("targetId") String targetId) {

        String userId = requireUserId(headerUserId);
        BlockType blockType;
        try {
            blockType = BlockType.valueOf(blockTypeStr.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("未知的屏蔽维度类型: " + blockTypeStr);
        }

        userBlockApplicationService.removeBlock(userId, blockType, targetId);
        return ApiResponse.ok();
    }

    /**
     * 查询当前用户的全部明确屏蔽记录列表。
     *
     * @param headerUserId 网关透传用户账号 ID (必填)
     * @return 屏蔽记录列表响应
     */
    @GetMapping("/blocks")
    public ApiResponse<List<UserBlockResponse>> listBlocks(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {

        String userId = requireUserId(headerUserId);
        List<UserBlock> blocks = userBlockApplicationService.listBlocks(userId);
        List<UserBlockResponse> responses = blocks.stream()
                .map(b -> new UserBlockResponse(
                        b.getId(),
                        b.getUserId(),
                        b.getBlockType().name(),
                        b.getTargetId(),
                        b.getReason(),
                        b.getCreatedAt()
                ))
                .toList();

        return ApiResponse.ok(responses);
    }

    /**
     * 优雅提取用户 ID，优先读取显式 Header，其次读取 ThreadLocal 上下文。
     */
    private String resolveUserId(String headerUserId) {
        if (headerUserId != null && !headerUserId.isBlank()) {
            return headerUserId.trim();
        }
        return UserContext.getCurrentUserId().orElse(null);
    }

    /**
     * 强制要求用户身份存在，不存在时拒绝执行。
     */
    private String requireUserId(String headerUserId) {
        String userId = resolveUserId(headerUserId);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("当前操作需要登录身份");
        }
        return userId;
    }
}
