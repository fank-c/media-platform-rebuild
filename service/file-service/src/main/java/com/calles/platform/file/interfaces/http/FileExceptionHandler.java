package com.calles.platform.file.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.file.exception.FileOperationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * file-service 范围的异常协议适配器。
 *
 * <p>它不改变 auth/user 的错误语义，并避免记录可能含预签名 URL、对象 key 或凭据的原始 SDK 文本。
 */
@RestControllerAdvice(basePackages = "com.calles.platform.file.interfaces.http")
public class FileExceptionHandler {
  /** 仅记录异常分类，禁止直接打印存储 SDK 的完整响应。 */
  private static final Logger LOGGER = LoggerFactory.getLogger(FileExceptionHandler.class);

  /** 映射可预期文件业务失败到真实 HTTP 状态。 */
  @ExceptionHandler(FileOperationException.class)
  public ResponseEntity<ApiResponse<Void>> handleFile(FileOperationException exception) {
    return ResponseEntity.status(exception.getStatus())
        .body(new ApiResponse<>(exception.getStatus().value(), exception.getMessage(), null));
  }

  /** Servlet multipart 限额先于应用读取触发时仍返回 413。 */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleTooLarge(
      MaxUploadSizeExceededException exception) {
    return ResponseEntity.status(413).body(new ApiResponse<>(413, "文件超过允许大小", null));
  }

  /** JSON 绑定和 Bean Validation 参数失败统一为脱敏 400。 */
  @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
    return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请求参数不合法", null));
  }

  /** 记录未知异常类别，不输出可能带签名 URL 的 message 或完整堆栈。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
    LOGGER.error("文件接口发生未预期异常，type={}", exception.getClass().getSimpleName());
    return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "服务器内部错误", null));
  }
}
