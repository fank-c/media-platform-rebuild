package com.calles.platform.file.interfaces.http.dto;

/**
 * V2 安全直传初始化请求。
 *
 * <p>摘要由客户端计算后以小写十六进制提交；bucket、对象 key、Base64 checksum 和签名期限均由服务端决定。
 */
public record DirectUploadV2Request(
    /** 展示文件名，服务拒绝路径分隔符和控制字符。 */
    String originName,
    /** 客户端声明的正数字节数，确认时仍由对象 HEAD 复核。 */
    Long size,
    /** 可选声明 MIME，缺失时归一化为 application/octet-stream。 */
    String mime,
    /** 可选受控存储类型，首期仅支持 MINIO。 */
    String storageType,
    /** 64 位小写十六进制 SHA-256，服务端会转换为签名所需的 Base64 checksum。 */
    String sha256) {}
