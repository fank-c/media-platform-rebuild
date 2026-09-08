package com.calles.platform.file.exception;

/**
 * 对象存储适配层的分类失败。
 *
 * <p>调用方据此区分确切不存在、内容不一致和未知远端结果，不能把所有失败当作对象不存在。
 */
public class ObjectStorageException extends RuntimeException {
  /** 远端失败的可恢复与安全分类。 */
  public enum Category {
    /** 经 SDK 明确确认的对象不存在。 */
    NOT_FOUND,
    /** 对象内容或长度与文件元数据不一致。 */
    CONTENT_MISMATCH,
    /** 凭据、bucket 或请求配置错误，不应自动重试。 */
    CONFIGURATION,
    /** 网络、限流或服务端故障，可由上层在受控场景重新发起。 */
    UNAVAILABLE,
    /** 超时或连接断开，远端动作可能已经发生。 */
    UNKNOWN_RESULT
  }

  /** 分类值，不包含 SDK 原始响应。 */
  private final Category category;

  /**
   * @param category 失败分类
   * @param message 脱敏内部说明
   * @param cause SDK 原因
   */
  public ObjectStorageException(Category category, String message, Throwable cause) {
    super(message, cause);
    this.category = category;
  }

  /** @return 失败分类 */
  public Category getCategory() {
    return category;
  }
}
