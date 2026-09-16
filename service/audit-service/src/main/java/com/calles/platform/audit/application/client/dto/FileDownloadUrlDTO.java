package com.calles.platform.audit.application.client.dto;

import java.time.Instant;

/**
 * 文件短期下载预签名直链 DTO。
 *
 * <p>封装从文件微服务 (file-service) 返回的已签名对象下载链接及过期时间戳。</p>
 *
 * @param url 预签名可直接 GET 下载的完整对象存储 URL
 * @param expiresAt 链接有效截止时间戳
 */
public record FileDownloadUrlDTO(
        String url,
        Instant expiresAt
) {
}
