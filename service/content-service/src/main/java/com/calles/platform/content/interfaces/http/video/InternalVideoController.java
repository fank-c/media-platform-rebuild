package com.calles.platform.content.interfaces.http.video;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.content.application.stream.VideoStreamApplicationService;
import com.calles.platform.content.application.video.VideoAuditCallbackApplicationService;
import com.calles.platform.content.interfaces.http.dto.VideoRequests;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部微服务 RPC 与异步结果回调 HTTP API 控制器。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：专用于与同网段内部微服务（审核服务 audit-service、音视频转码任务集群等）协作的受信任内部适配层；</li>
 *   <li><b>核心用例</b>：接收审核异步判定结果打标回调、接收流媒体切片异步转码就绪回调；</li>
 *   <li><b>安全与幂等</b>：不直接面向公网客户端暴露，在业务层实现强幂等防护（重复回调静默忽略或安全覆盖）；</li>
 *   <li><b>不应承担的工作</b>：不接收普通用户业务请求，不处理用户级鉴权上下文。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/content/videos/internal")
@RequiredArgsConstructor
public class InternalVideoController {

    /** 审核判定异步结果回调处理服务。 */
    private final VideoAuditCallbackApplicationService auditCallbackService;

    /** 视频转码流注册与调度应用服务。 */
    private final VideoStreamApplicationService streamService;

    /**
     * 接收审核微服务 (audit-service) 的审核判定结果回调。
     *
     * @param request 包含审核通过与否及驳回原因的回调参数
     * @return 标准成功响应
     */
    @PostMapping("/audit-callback")
    public ApiResponse<Void> auditCallback(@Valid @RequestBody VideoRequests.AuditCallback request) {
        // 步骤 1：委托审核回调服务完成发布状态流转与事务性 Outbox 事件写入（幂等安全）
        auditCallbackService.handleAuditCallback(request);
        return ApiResponse.ok();
    }

    /**
     * 接收媒体转码微服务的转码产物注册回调。
     *
     * @param request 包含各画质清晰度、文件资产 ID 与格式的回调参数
     * @return 标准成功响应
     */
    @PostMapping("/transcode-callback")
    public ApiResponse<Void> transcodeCallback(@Valid @RequestBody VideoRequests.TranscodeCallback request) {
        // 步骤 1：登记或刷新指定视频的清晰度转码切片信息
        streamService.registerStream(request);
        return ApiResponse.ok();
    }
}
