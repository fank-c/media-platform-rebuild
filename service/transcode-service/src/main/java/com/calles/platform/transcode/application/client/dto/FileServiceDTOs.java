package com.calles.platform.transcode.application.client.dto;

import java.time.Instant;

/**
 * 文件服务 (file-service) Feign 交互传输对象集合。
 */
public final class FileServiceDTOs {

    private FileServiceDTOs() {}

    /** 文件临时预签名下载地址响应体。 */
    public record DownloadUrlResponse(String url, Instant expiresAt) {}

    /** 内部托管上传完成元数据响应体。 */
    public record FileUploadResponse(
            String fileId,
            String originName,
            String mime,
            long declaredSize,
            Long actualSize,
            String sha256,
            String status,
            String uploadStatus
    ) {}
}
