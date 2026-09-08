package com.calles.platform.file.interfaces.http.dto;

/**
 * 预签名直传初始化请求。
 *
 * <p>客户端只能声明展示名、MIME、大小和受控存储类型；不能指定对象 key、bucket、摘要或签名期限。
 */
public record DirectUploadRequest(
    /** 展示文件名，服务拒绝路径分隔符和控制字符。 */
    String originName,
    /** 客户端声明的正整数大小；服务确认时以实际读取结果复核。 */
    Long size,
    /** 可选声明 MIME，缺失时归一化为 application/octet-stream。 */
    String mime,
    /** 可选受控存储类型，首期仅支持 MINIO。 */
    String storageType) {}
