package com.calles.platform.audit.application.client;

import com.calles.platform.audit.application.client.dto.FileDownloadUrlDTO;
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
 *   <li><b>所属边界</b>：审核服务与文件资产中心的同步 RPC 防腐边界；</li>
 *   <li><b>协作对象</b>：与下游 {@code file-service} 通信，通过请求头透传作者身份凭证 (X-User-Id / X-User-Role)；</li>
 *   <li><b>核心用途</b>：为待审封面图片与音视频文件申请具有时效性的公网 MinIO 预签名 GET 下载直链，供阿里云审核服务直连拉流。</li>
 * </ul>
 * </p>
 */
@FeignClient(name = "file-service", path = "/api/files")
public interface FileServiceClient {

    /**
     * 根据文件主键 ID 申请短期预签名 GET 下载直链。
     *
     * @param id 文件全局唯一标识 (UUID)
     * @param userId 资源拥有者用户 ID (用于下游权限校验)
     * @param userRole 用户角色 (默认为 USER)
     * @return 包含预签名直链信息的统一响应封装
     */
    @GetMapping("/{id}/download-url")
    ApiResponse<FileDownloadUrlDTO> getDownloadUrl(
            @PathVariable("id") String id,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Role") String userRole
    );
}
