package com.calles.platform.file.infrastructure.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.CopyObjectRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.exception.ObjectStorageException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 对象存储适配器。
 *
 * <p>实现 {@link ObjectStorageClient} 受控端口契约，集中翻译 OSS SDK 异常并隔离 Bucket、端点与签名细节，
 * 严禁将 SDK 类型或凭据向上层泄露。
 */
@Component
@ConditionalOnBean(name = "fileStorageOssClient")
public class AliyunOssObjectStorageClient implements ObjectStorageClient {

  /** 管理对象读写的 OSS SDK 客户端（内网或管理接入点）。 */
  private final OSS storageClient;

  /** 专门按客户端或公网审核服务可达端点生成预签名 URL 的 OSS SDK 客户端。 */
  private final OSS presignClient;

  /** 本服务专用的私有 Bucket 名称。 */
  private final String bucket;

  /**
   * 构造阿里云 OSS 存储适配器。
   *
   * @param storageClient 管理端点客户端
   * @param presignClient 预签名端点客户端
   * @param properties 文件存储已校验配置参数
   */
  public AliyunOssObjectStorageClient(
      @Qualifier("fileStorageOssClient") OSS storageClient,
      @Qualifier("filePresignOssClient") OSS presignClient,
      FileStorageProperties properties) {
    this.storageClient = storageClient;
    this.presignClient = presignClient;
    this.bucket = properties.getOss().getBucket();
  }

  /**
   * 返回此适配器负责的存储类型。
   *
   * @return StorageType.ALIYUN_OSS
   */
  @Override
  public StorageType storageType() {
    return StorageType.ALIYUN_OSS;
  }

  /**
   * 服务端流式上传对象到私有 Bucket。
   *
   * @param key 对象唯一键
   * @param mime 文件媒体类型
   * @param input 输入流
   * @param size 流大小字节数
   */
  @Override
  public void put(String key, String mime, InputStream input, long size) {
    try {
      // 步骤1：组装对象元数据（设置 MIME 与实际长度）
      ObjectMetadata metadata = new ObjectMetadata();
      if (mime != null && !mime.isBlank()) {
        metadata.setContentType(mime);
      }
      if (size >= 0) {
        metadata.setContentLength(size);
      }

      // 步骤2：执行流式上传，由调用方负责输入流生命周期
      PutObjectRequest request = new PutObjectRequest(bucket, key, input, metadata);
      storageClient.putObject(request);
    } catch (Exception exception) {
      throw translate("上传对象失败", exception);
    }
  }

  /**
   * 查询对象元数据（HEAD）。
   *
   * @param key 对象键
   * @return 观察到的对象元数据
   */
  @Override
  public ObjectHead head(String key) {
    try {
      // 步骤1：向 OSS 发送 HEAD 请求读取对象元数据
      ObjectMetadata metadata = storageClient.getObjectMetadata(bucket, key);
      return new ObjectHead(
          metadata.getContentLength(),
          metadata.getETag(),
          metadata.getLastModified() == null ? null : metadata.getLastModified().toInstant(),
          metadata.getContentType());
    } catch (Exception exception) {
      throw translate("查询对象失败", exception);
    }
  }

  /**
   * 打开全新的对象输入流。
   *
   * @param key 对象键
   * @return 对象内容流，调用者必须显式关闭
   */
  @Override
  public InputStream open(String key) {
    try {
      // 步骤1：获取远端 OSS 对象并提取输入流
      OSSObject object = storageClient.getObject(bucket, key);
      return object.getObjectContent();
    } catch (Exception exception) {
      throw translate("读取对象失败", exception);
    }
  }

  /**
   * 删除指定的 OSS 对象。
   *
   * @param key 对象键
   */
  @Override
  public void delete(String key) {
    try {
      // 步骤1：向 OSS 发起删除对象请求
      storageClient.deleteObject(bucket, key);
    } catch (Exception exception) {
      throw translate("删除对象失败", exception);
    }
  }

  /**
   * 生成旧 V1 PUT 临时凭据。
   *
   * @param key 对象键
   * @param mime 受控媒体类型
   * @param ttl 预签名有效期
   * @return 预签名 URL 与受控 Header
   */
  @Override
  public PresignedUrl presignPut(String key, String mime, Duration ttl) {
    Map<String, String> headers =
        (mime != null && !mime.isBlank()) ? Map.of("Content-Type", mime) : Map.of();
    return presignPutWithHeaders(key, headers, ttl);
  }

  /**
   * 生成带 checksum 要求的 V2 PUT 临时凭据。
   *
   * <p>当前阶段保持 V2 暂存直传关闭，在未完成真实 OSS checksum POC 前显式拒绝调用。
   *
   * @param key 暂存对象 key
   * @param mime 媒体类型
   * @param checksumBase64 期望 SHA-256 Base64 编码
   * @param ttl 有效期
   * @return 预签名 URL
   */
  @Override
  public PresignedUrl presignPut(String key, String mime, String checksumBase64, Duration ttl) {
    throw new UnsupportedOperationException("阿里云 OSS 暂未开启 V2 checksum PUT 签名支持，需先完成真实 POC 验证");
  }

  /**
   * 以源 ETag 为条件执行服务端对象复制，不经过本地下载中转。
   *
   * @param sourceKey staging 源 key
   * @param targetKey permanent 目标 key
   * @param sourceEtag HEAD 阶段获取的 ETag
   */
  @Override
  public void copy(String sourceKey, String targetKey, String sourceEtag) {
    try {
      // 步骤1：构造复制请求，跨目录同 Bucket 复制
      CopyObjectRequest request = new CopyObjectRequest(bucket, sourceKey, bucket, targetKey);
      if (sourceEtag != null && !sourceEtag.isBlank()) {
        // 步骤2：施加源 ETag 匹配约束（条件复制），防止并发篡改
        request.setMatchingETagConstraints(List.of(sourceEtag));
      }
      storageClient.copyObject(request);
    } catch (Exception exception) {
      throw translateCopy(exception);
    }
  }

  /**
   * 生成公网 HTTPS GET 临时预签名下载链接。
   *
   * @param key 对象键
   * @param ttl 签名有效期
   * @return 预签名 URL
   */
  @Override
  public PresignedUrl presignGet(String key, Duration ttl) {
    try {
      // 步骤1：计算过期绝对时间戳
      Date expiration = Date.from(Instant.now().plus(ttl));

      // 步骤2：通过预签名专用客户端生成可公网解析的签名 URL
      GeneratePresignedUrlRequest request =
          new GeneratePresignedUrlRequest(bucket, key, HttpMethod.GET);
      request.setExpiration(expiration);
      URL url = presignClient.generatePresignedUrl(request);

      return new PresignedUrl(url.toString(), Map.of(), expiration.toInstant());
    } catch (Exception exception) {
      throw translate("生成下载签名失败", exception);
    }
  }

  /**
   * 携带必要受控 Headers 生成 PUT 预签名 URL。
   *
   * @param key 对象键
   * @param headers 客户端必须原样附带的 Header 映射
   * @param ttl 签名有效时长
   * @return 预签名结果
   */
  private PresignedUrl presignPutWithHeaders(
      String key, Map<String, String> headers, Duration ttl) {
    try {
      // 步骤1：计算过期绝对时间戳
      Date expiration = Date.from(Instant.now().plus(ttl));

      // 步骤2：组装 PUT 签名请求并绑定受控请求头
      GeneratePresignedUrlRequest request =
          new GeneratePresignedUrlRequest(bucket, key, HttpMethod.PUT);
      request.setExpiration(expiration);
      if (headers != null) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
          if ("Content-Type".equalsIgnoreCase(entry.getKey())) {
            request.setContentType(entry.getValue());
          } else {
            request.addHeader(entry.getKey(), entry.getValue());
          }
        }
      }

      // 步骤3：生成合法签名 URL
      URL url = presignClient.generatePresignedUrl(request);
      return new PresignedUrl(url.toString(), headers == null ? Map.of() : headers, expiration.toInstant());
    } catch (Exception exception) {
      throw translate("生成上传签名失败", exception);
    }
  }

  /**
   * 将 OSS SDK 异常安全翻译为业务层统一的受控异常，严防端点或凭据外泄。
   *
   * @param action 执行的动作说明
   * @param exception 捕获的底层异常
   * @return 领域受控异常
   */
  private ObjectStorageException translate(String action, Exception exception) {
    if (exception instanceof OSSException ossException && isNotFound(ossException)) {
      return new ObjectStorageException(
          ObjectStorageException.Category.NOT_FOUND, action, exception);
    }
    return new ObjectStorageException(
        ObjectStorageException.Category.UNAVAILABLE, action, exception);
  }

  /**
   * 将条件复制异常安全翻译为领域异常。
   *
   * @param exception 复制时抛出的底层异常
   * @return 区分内容不满足与结果未确认的受控异常
   */
  private ObjectStorageException translateCopy(Exception exception) {
    if (exception instanceof OSSException ossException) {
      String code = ossException.getErrorCode() == null ? "" : ossException.getErrorCode();
      if (isNotFound(ossException)) {
        return new ObjectStorageException(
            ObjectStorageException.Category.NOT_FOUND, "复制对象失败", exception);
      }
      if ("PreconditionFailed".equalsIgnoreCase(code) || "InvalidRequest".equalsIgnoreCase(code)) {
        return new ObjectStorageException(
            ObjectStorageException.Category.CONTENT_MISMATCH, "复制条件未满足", exception);
      }
    }
    return new ObjectStorageException(
        ObjectStorageException.Category.UNKNOWN_RESULT, "复制对象结果未确认", exception);
  }

  /**
   * 判定底层错误码是否明确为对象不存在。
   *
   * @param exception OSS 异常
   * @return 是否为明确的键缺失
   */
  private boolean isNotFound(OSSException exception) {
    String code = exception.getErrorCode() == null ? "" : exception.getErrorCode();
    return "NoSuchKey".equalsIgnoreCase(code) || "NoSuchObject".equalsIgnoreCase(code);
  }
}
