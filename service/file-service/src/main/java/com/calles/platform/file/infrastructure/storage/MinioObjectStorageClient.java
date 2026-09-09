package com.calles.platform.file.infrastructure.storage;

import com.calles.platform.file.application.port.ObjectStorageClient;
import com.calles.platform.file.config.FileStorageProperties;
import com.calles.platform.file.domain.asset.StorageType;
import com.calles.platform.file.exception.ObjectStorageException;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** MinIO 对象存储适配器，集中翻译 SDK 异常并隔离 bucket、端点与签名细节。 */
@Component
public class MinioObjectStorageClient implements ObjectStorageClient {
  /** 管理对象读写的 SDK 客户端。 */
  private final MinioClient storageClient;

  /** 专门按客户端可达端点生成 URL 的 SDK 客户端。 */
  private final MinioClient presignClient;

  /** 本服务专用私有 bucket。 */
  private final String bucket;

  /**
   * @param storageClient 管理端点客户端
   * @param filePresignClient 签名端点客户端
   * @param properties 已校验存储参数
   */
  public MinioObjectStorageClient(
      @Qualifier("fileStorageMinioClient") MinioClient storageClient,
      @Qualifier("filePresignMinioClient") MinioClient filePresignClient,
      FileStorageProperties properties) {
    this.storageClient = storageClient;
    this.presignClient = filePresignClient;
    this.bucket = properties.getMinio().getBucket();
  }

  /**
   * @return MINIO
   */
  @Override
  public StorageType storageType() {
    return StorageType.MINIO;
  }

  /** 流式 PUT，调用方负责输入流生命周期和实际字节/摘要核验。 */
  @Override
  public void put(String key, String mime, InputStream input, long size) {
    try {
      storageClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).stream(input, size, -1)
              .contentType(mime)
              .build());
    } catch (Exception exception) {
      throw translate("上传对象失败", exception);
    }
  }

  /** 读取当前对象观察元数据，ETag 只作为覆盖检测的有限信号。 */
  @Override
  public ObjectHead head(String key) {
    try {
      StatObjectResponse stat =
          storageClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
      return new ObjectHead(
          stat.size(),
          stat.etag(),
          stat.lastModified() == null ? null : stat.lastModified().toInstant(),
          stat.contentType());
    } catch (Exception exception) {
      throw translate("查询对象失败", exception);
    }
  }

  /** 打开一条全新的远端流；调用者必须用 try-with-resources 关闭。 */
  @Override
  public InputStream open(String key) {
    try {
      return storageClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception exception) {
      throw translate("读取对象失败", exception);
    }
  }

  /** 执行远端删除；超时等未知结果由异常分类保留给业务层。 */
  @Override
  public void delete(String key) {
    try {
      storageClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception exception) {
      throw translate("删除对象失败", exception);
    }
  }

  /** 生成旧 V1 只含受控 Content-Type 要求的 PUT 签名。 */
  @Override
  public PresignedUrl presignPut(String key, String mime, Duration ttl) {
    return presignPutWithHeaders(key, Map.of("Content-Type", mime), ttl);
  }

  /** 生成 V2 checksum PUT 签名；checksum header 同时参与签名和对象存储校验。 */
  @Override
  public PresignedUrl presignPut(String key, String mime, String checksumBase64, Duration ttl) {
    return presignPutWithHeaders(
        key, Map.of("Content-Type", mime, "x-amz-checksum-sha256", checksumBase64), ttl);
  }

  /** 以 SDK 的 extraHeaders 生成签名，确保客户端必须原样带上受控请求头。 */
  private PresignedUrl presignPutWithHeaders(
      String key, Map<String, String> headers, Duration ttl) {
    try {
      String url =
          presignClient.getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(Method.PUT)
                  .bucket(bucket)
                  .object(key)
                  .expiry(toSeconds(ttl), TimeUnit.SECONDS)
                  .extraHeaders(headers)
                  .build());
      return new PresignedUrl(url, headers, Instant.now().plus(ttl));
    } catch (Exception exception) {
      throw translate("生成上传签名失败", exception);
    }
  }

  /** 使用源 ETag 条件执行服务端 copy，避免 file-service 下载 staging 文件。 */
  @Override
  public void copy(String sourceKey, String targetKey, String sourceEtag) {
    try {
      storageClient.copyObject(
          CopyObjectArgs.builder()
              .bucket(bucket)
              .object(targetKey)
              .source(
                  CopySource.builder()
                      .bucket(bucket)
                      .object(sourceKey)
                      .matchETag(sourceEtag)
                      .build())
              .build());
    } catch (Exception exception) {
      throw translateCopy(exception);
    }
  }

  /** 生成短期 GET 签名；调用方必须先验证自身所有权与完成状态。 */
  @Override
  public PresignedUrl presignGet(String key, Duration ttl) {
    try {
      String url =
          presignClient.getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(Method.GET)
                  .bucket(bucket)
                  .object(key)
                  .expiry(toSeconds(ttl), TimeUnit.SECONDS)
                  .build());
      return new PresignedUrl(url, Map.of(), Instant.now().plus(ttl));
    } catch (Exception exception) {
      throw translate("生成下载签名失败", exception);
    }
  }

  /** 将 SDK 异常转换为不泄漏端点或凭据的领域分类。 */
  private ObjectStorageException translate(String action, Exception exception) {
    if (exception instanceof ErrorResponseException response && isNotFound(response)) {
      return new ObjectStorageException(
          ObjectStorageException.Category.NOT_FOUND, action, exception);
    }
    return new ObjectStorageException(
        ObjectStorageException.Category.UNAVAILABLE, action, exception);
  }

  /** 条件 copy 的 ETag 不匹配是内容竞争，不应伪造完成；其他异常结果仍按未知失败处理。 */
  private ObjectStorageException translateCopy(Exception exception) {
    if (exception instanceof ErrorResponseException response) {
      String code = response.errorResponse() == null ? "" : response.errorResponse().code();
      if (isNotFound(response)) {
        return new ObjectStorageException(
            ObjectStorageException.Category.NOT_FOUND, "复制对象失败", exception);
      }
      if ("PreconditionFailed".equals(code) || "InvalidRequest".equals(code)) {
        return new ObjectStorageException(
            ObjectStorageException.Category.CONTENT_MISMATCH, "复制条件未满足", exception);
      }
    }
    return new ObjectStorageException(
        ObjectStorageException.Category.UNKNOWN_RESULT, "复制对象结果未确认", exception);
  }

  /** 仅将明确对象缺失码分类为不存在，bucket 缺失和权限不足不能混同。 */
  private boolean isNotFound(ErrorResponseException exception) {
    String code = exception.errorResponse() == null ? "" : exception.errorResponse().code();
    return "NoSuchKey".equals(code) || "NoSuchObject".equals(code);
  }

  /** 将 Duration 收敛为 MinIO SDK 要求的正秒数。 */
  private int toSeconds(Duration duration) {
    long seconds = Math.max(1, duration.toSeconds());
    if (seconds > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("签名期限超出 SDK 范围");
    }
    return (int) seconds;
  }
}
