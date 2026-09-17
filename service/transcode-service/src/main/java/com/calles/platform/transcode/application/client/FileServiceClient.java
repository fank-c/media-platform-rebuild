package com.calles.platform.transcode.application.client;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.transcode.application.client.dto.FileServiceDTOs;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件微服务 (file-service) 受信任内部 Feign 声明式客户端。
 */
@FeignClient(name = "file-service", configuration = FeignMultipartConfiguration.class)
public interface FileServiceClient {

    /**
     * 按文件资产 ID 获取用于流式拉取原片的临时下载预签名直链。
     */
    @GetMapping("/api/files/internal/{id}/download-url")
    ApiResponse<FileServiceDTOs.DownloadUrlResponse> getInternalDownloadUrl(@PathVariable("id") String id);

    /**
     * 内部免 Session 托管上传转码完成后的多媒体切片。
     */
    @PostMapping(value = "/api/files/internal/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResponse<FileServiceDTOs.FileUploadResponse> uploadInternal(
            @RequestPart("file") MultipartFile file,
            @RequestParam("authorId") String authorId,
            @RequestParam(value = "storageType", required = false) String storageType
    );
}
