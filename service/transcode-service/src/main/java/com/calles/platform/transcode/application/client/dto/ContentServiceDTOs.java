package com.calles.platform.transcode.application.client.dto;

/**
 * 内容服务 (content-service) Feign 交互传输对象集合。
 */
public final class ContentServiceDTOs {

    private ContentServiceDTOs() {}

    /**
     * 转码产物切片登记回调传输对象。
     */
    public record TranscodeCallbackRequest(
            String videoId,
            String quality,
            String format,
            String codec,
            String fileId,
            Long fileSize,
            Integer bitrate,
            Integer fps,
            String transcodeStatus,
            Integer duration
    ) {}

    /**
     * 视频流水线任务进度与执行状态汇报传输对象。
     */
    public record TaskCallbackRequest(
            String videoId,
            String taskType,
            String status,
            Integer progress,
            String errorMessage
    ) {}
}
