package com.calles.platform.file.interfaces.http.dto;

import java.time.Instant;
import java.util.Map;

/** 文件 HTTP 响应集合，避免将对象 key、bucket、SDK 异常或管理端点泄露到外部契约。 */
public final class FileResponses {
  /** 工具命名空间禁止实例化。 */
  private FileResponses() {}

  /** 私人文件元数据；actualSize 在 PENDING/EXPIRED 时允许为空。 */
  public record Metadata(
      String fileId,
      String originName,
      String mime,
      long declaredSize,
      Long actualSize,
      String sha256,
      String status,
      String uploadStatus,
      Instant uploadExpiresAt,
      Instant createdAt,
      Instant updatedAt) {}

  /** 预签名 PUT 初始化响应，URL 是短期敏感凭据，不应写入日志或浏览器缓存。 */
  public record DirectUpload(
      String fileId,
      String uploadStatus,
      String putUrl,
      Map<String, String> requiredHeaders,
      Instant putExpiresAt,
      Instant uploadExpiresAt) {}

  /** 确认仍在本进程处理或已入队时返回的异步状态。 */
  public record ConfirmationAccepted(String fileId, String uploadStatus, long pollAfterSeconds) {}

  /** 下载签名响应，持有人可直接访问对象入口，必须以 no-store 返回。 */
  public record DownloadUrl(String url, Instant expiresAt) {}

  /** 静态资源受控预览防盗链签名直链响应。 */
  public record ViewUrl(String url, Instant expiresAt) {}
}
