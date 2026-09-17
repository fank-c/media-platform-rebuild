package com.calles.platform.transcode.application.client;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.transcode.application.client.dto.ContentServiceDTOs;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 内容微服务 (content-service) 受信任内部 Feign 声明式客户端。
 */
@FeignClient(name = "content-service")
public interface ContentServiceClient {

    /**
     * 向内容服务登记已完成的视频转码清晰度切片信息。
     */
    @PostMapping("/api/content/videos/internal/transcode-callback")
    ApiResponse<Void> transcodeCallback(@RequestBody ContentServiceDTOs.TranscodeCallbackRequest request);

    /**
     * 向视频异步任务协调器汇报流水线转码任务进度与状态。
     */
    @PostMapping("/api/content/videos/internal/task-callback")
    ApiResponse<Void> taskCallback(@RequestBody ContentServiceDTOs.TaskCallbackRequest request);
}
