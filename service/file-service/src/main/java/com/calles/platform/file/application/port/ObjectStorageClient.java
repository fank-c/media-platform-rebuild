package com.calles.platform.file.application.port;

import com.calles.platform.file.domain.asset.StorageType;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 文件服务对对象存储的受控端口。
 *
 * <p>参数只接收服务端生成的 key，禁止由 HTTP 参数传入 bucket、URL 或任意远程地址。
 */
public interface ObjectStorageClient {
  /** @return 此适配器负责的存储类型 */
  StorageType storageType();

  /**
   * 流式上传对象，不接收或保留整个文件的内存副本。
   *
   * @param key 服务端生成的对象 key
   * @param mime 声明的内容类型
   * @param input 单次使用的输入流
   * @param size 声明字节数
   */
  void put(String key, String mime, InputStream input, long size);

  /**
   * @param key 对象 key
   * @return 对象当前可观察元数据
   */
  ObjectHead head(String key);

  /**
   * @param key 对象 key
   * @return 调用方必须关闭的全新对象流
   */
  InputStream open(String key);

  /**
   * 删除对象；只有明确成功或明确不存在才可由调用方继续写逻辑墓碑。
   *
   * @param key 对象 key
   */
  void delete(String key);

  /**
   * 生成 PUT 临时凭据。
   *
   * @param key 对象 key
   * @param mime 经服务端规范化的 MIME
   * @param ttl 有效期
   * @return 临时 URL 与客户端必须提供的请求头
   */
  PresignedUrl presignPut(String key, String mime, Duration ttl);

  /**
   * @param key 对象 key
   * @param ttl 有效期
   * @return 短期 GET 临时凭据
   */
  PresignedUrl presignGet(String key, Duration ttl);

  /** 对象 HEAD 的有限观测结果；ETag 不是 SHA-256。 */
  record ObjectHead(long size, String etag, Instant lastModified, String contentType) {}

  /** 临时 URL 与需要客户端原样带上的受控 Header。 */
  record PresignedUrl(String url, Map<String, String> requiredHeaders, Instant expiresAt) {}
}
