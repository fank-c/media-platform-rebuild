package com.calles.platform.recommend.application.client.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 向内容微服务汇报任务进度与结果的回调请求载荷模型。
 *
 * @param videoId 视频内部全局主键 ID
 * @param taskType 任务类型 (固定为 VECTOR_EMBEDDING)
 * @param status 状态 (RUNNING, SUCCESS, FAILED)
 * @param progress 进度百分比 (0-100)
 * @param errorMessage 失败错误描述信息
 */
public record TaskCallbackRequest(
        @NotBlank String videoId,
        @NotBlank String taskType,
        @NotBlank String status,
        Integer progress,
        String errorMessage
) {
    /**
     * 快捷构建成功完成回调对象。
     *
     * @param videoId 视频全局 ID
     * @return 成功回调请求体
     */
    public static TaskCallbackRequest success(String videoId) {
        return new TaskCallbackRequest(videoId, "VECTOR_EMBEDDING", "SUCCESS", 100, null);
    }

    /**
     * 快捷构建失败报错回调对象。
     *
     * @param videoId 视频全局 ID
     * @param errorMessage 错误原因
     * @return 失败回调请求体
     */
    public static TaskCallbackRequest failed(String videoId, String errorMessage) {
        return new TaskCallbackRequest(videoId, "VECTOR_EMBEDDING", "FAILED", null, errorMessage);
    }
}
