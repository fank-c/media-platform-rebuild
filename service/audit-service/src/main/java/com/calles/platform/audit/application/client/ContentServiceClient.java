package com.calles.platform.audit.application.client;

import com.calles.platform.common.core.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 内容微服务 (content-service) OpenFeign 远程通信客户端。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务应用层服务间远程 RPC 调用端口契约；</li>
 *   <li><b>目标端点</b>：内容微服务专属内部回调端点 {@code POST /api/content/videos/internal/audit-callback}；</li>
 *   <li><b>业务联动</b>：将审核通过 (PASSED) 或驳回 (REJECTED) 及其原因安全送达内容微服务，驱动其分级门禁流转。</li>
 * </ul>
 * </p>
 */
@FeignClient(name = "content-service")
public interface ContentServiceClient {

    /**
     * 审核结果回调载荷模型。
     *
     * @param videoId 视频内部全局主键 ID (UUID 32位)
     * @param passed 是否通过审核 (true 为放行，false 为驳回)
     * @param rejectReason 审核驳回原因（未通过时必填，通过时为 null）
     */
    record AuditCallbackPayload(
            @NotBlank String videoId,
            @NotNull Boolean passed,
            String rejectReason
    ) {}

    /**
     * 向内容微服务通知审核终局裁决结果回调。
     *
     * @param payload 包含视频 ID、结论与驳回原因的载荷体
     * @return 内容微服务的统一空数据响应体
     */
    @PostMapping("/api/content/videos/internal/audit-callback")
    ApiResponse<Void> notifyAuditResult(@RequestBody AuditCallbackPayload payload);
}
