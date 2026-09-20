package com.calles.platform.recommend.application.client;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.recommend.application.client.dto.TaskCallbackRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 内容微服务 (content-service) OpenFeign 声明式 RPC 客户端。
 *
 * <p>职责与端点契约：
 * <ul>
 *   <li><b>所属边界</b>：应用层微服务间 RPC 通信端口契约；</li>
 *   <li><b>目标端点</b>：{@code POST /api/content/videos/internal/task-callback}；</li>
 *   <li><b>发布门禁联动</b>：汇报 {@code VECTOR_EMBEDDING} 任务成功，驱动内容服务门禁自动放行发布。</li>
 * </ul>
 * </p>
 */
@FeignClient(name = "content-service")
public interface ContentServiceClient {

    /**
     * 向内容微服务任务协调器汇报流水线子任务状态与进度。
     *
     * @param request 任务回调参数
     * @return 统一响应体
     */
    @PostMapping("/api/content/videos/internal/task-callback")
    ApiResponse<Void> taskCallback(@RequestBody TaskCallbackRequest request);
}
