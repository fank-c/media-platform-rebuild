package com.calles.platform.content.application.client;

import com.calles.platform.common.core.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 远程调用文件微服务 (file-service) 的声明式 OpenFeign 客户端。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：内容服务与文件资产域的同步 RPC 契约防腐边界；</li>
 *   <li><b>协作对象</b>：与下游 {@code file-service} 通信，通过请求头透传调用方身份上下文 (X-User-Id / X-User-Role)；</li>
 *   <li><b>核心用途</b>：在创作者创建草稿、更新封面或提交审核时，向文件中心核验视频源文件与封面图片的合法性及上传就绪状态。</li>
 * </ul>
 * </p>
 */
@FeignClient(name = "file-service", path = "/api/files")
public interface FileServiceClient {

    /**
     * 根据文件主键 ID 查询文件核心元数据。
     *
     * @param id 文件全局唯一标识 (UUID)
     * @param userId 调用者用户 ID，透传至下游用于归属权校验
     * @param userRole 调用者角色 (如 USER / ADMIN)
     * @return 包含文件元数据的统一 API 响应
     */
    @GetMapping("/{id}")
    ApiResponse<FileMetadataDTO> getFileMetadata(
            @PathVariable("id") String id,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Role") String userRole
    );
}
