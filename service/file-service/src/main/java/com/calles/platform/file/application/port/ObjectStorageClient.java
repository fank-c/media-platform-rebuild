package com.calles.platform.file.application.port;

import com.calles.platform.file.domain.asset.StorageType;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 文件服务对对象存储的受控端口。
 *
 * <p>参数只接收服务端生成的 key，禁止由 HTTP 参数传入 bucket、URL 或任意远程地址。V2 通过 checksum 绑定 PUT，并通过 ETag 条件 copy 避免
 * HEAD 与迁移之间复制被替换的 staging 对象。
 */
public interface ObjectStorageClient {
  /**
   * @return 此适配器负责的存储类型
   */
  StorageType storageType();

  /** 流式上传对象；调用方负责输入流生命周期和实际字节/摘要核验。 */
  void put(String key, String mime, InputStream input, long size);

  /**
   * @param key 对象 key @return 对象当前可观察元数据
   */
  ObjectHead head(String key);

  /**
   * @param key 对象 key @return 调用方必须关闭的全新对象流
   */
  InputStream open(String key);

  /** 删除对象；只有明确成功或明确不存在才可由调用方继续写逻辑墓碑。 */
  void delete(String key);

  /**
   * 生成旧 V1 PUT 临时凭据。
   *
   * <p>保留该方法用于兼容窗口；新客户端必须使用带 checksum 的重载。
   */
  PresignedUrl presignPut(String key, String mime, Duration ttl);

  /**
   * 生成带服务端 checksum 要求的 V2 PUT 临时凭据。
   *
   * @param key 仅允许 staging key
   * @param mime 经服务端规范化的 MIME
   * @param checksumBase64 原始 SHA-256 的 Base64 编码
   * @param ttl 有效期
   * @return 临时 URL 与客户端必须原样发送的请求头
   */
  default PresignedUrl presignPut(String key, String mime, String checksumBase64, Duration ttl) {
    throw new UnsupportedOperationException("当前对象存储适配器不支持 checksum PUT");
  }

  /**
   * 以源 ETag 为条件执行服务端对象复制，不经过 file-service 下载或重新上传。
   *
   * @param sourceKey staging 源 key
   * @param targetKey permanent 目标 key
   * @param sourceEtag HEAD 观察到的 ETag
   */
  default void copy(String sourceKey, String targetKey, String sourceEtag) {
    throw new UnsupportedOperationException("当前对象存储适配器不支持条件 copy");
  }

  /**
   * @param key 对象 key @param ttl 有效期 @return 短期 GET 临时凭据
   */
  PresignedUrl presignGet(String key, Duration ttl);

  /** 对象 HEAD 的有限观测结果；ETag 不是 SHA-256。 */
  record ObjectHead(long size, String etag, Instant lastModified, String contentType) {}

  /** 临时 URL 与需要客户端原样带上的受控 Header。 */
  record PresignedUrl(String url, Map<String, String> requiredHeaders, Instant expiresAt) {}
}
