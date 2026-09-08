package com.calles.platform.file.exception;

import org.springframework.http.HttpStatus;

/** 文件用例可预期失败，携带对外 HTTP 状态且不暴露对象存储地址、凭据或 SDK 细节。 */
public class FileOperationException extends RuntimeException {
  /** 对外返回的真实 HTTP 状态。 */
  private final HttpStatus status;

  /**
   * 创建无内部原因的业务失败。
   *
   * @param status HTTP 状态
   * @param message 面向客户端的中文提示
   */
  public FileOperationException(HttpStatus status, String message) {
    super(message);
    this.status = status;
  }

  /**
   * 创建保留内部原因但不直接暴露的业务失败。
   *
   * @param status HTTP 状态
   * @param message 面向客户端的中文提示
   * @param cause 内部异常原因
   */
  public FileOperationException(HttpStatus status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  /** @return 应写入响应的 HTTP 状态 */
  public HttpStatus getStatus() {
    return status;
  }
}
