package com.calles.platform.transcode.interfaces.http.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 转码微服务 HTTP 请求传输对象 (DTO) 集合。
 */
public final class TranscodeRequests {

    private TranscodeRequests() {}

    /**
     * 手动触发指定视频清晰度转码压制请求体。
     *
     * @param videoId 关联的视频内部全局主键 ID（必填）
     * @param authorId 创作者账号 ID（必填）
     * @param sourceFileId 原始待转码文件资产 ID（必填）
     * @param quality 目标画质规格（选填，默认 720P）
     */
    public record TriggerTranscode(
            @NotBlank(message = "videoId 不能为空")
            String videoId,

            @NotBlank(message = "authorId 不能为空")
            String authorId,

            @NotBlank(message = "sourceFileId 不能为空")
            String sourceFileId,

            String quality
    ) {}
}
