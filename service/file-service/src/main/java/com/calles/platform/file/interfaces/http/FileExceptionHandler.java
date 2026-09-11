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
    LOGGER.warn("文件业务异常: status={}, message={}", exception.getStatus().value(), exception.getMessage());
    return ResponseEntity.status(exception.getStatus())
        .body(new ApiResponse<>(exception.getStatus().value(), exception.getMessage(), null));
  }

  /** Servlet multipart 限额先于应用读取触发时仍返回 413。 */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleTooLarge(
      MaxUploadSizeExceededException exception) {
    LOGGER.warn("文件上传超过大小限额: {}", exception.getMessage());
    return ResponseEntity.status(413).body(new ApiResponse<>(413, "文件超过允许大小", null));
  }

  /** JSON 绑定和 Bean Validation 参数失败统一为脱敏 400。 */
  @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
    // 步骤 1：准备默认错误响应文案
    String message = "请求参数不合法";

    // 步骤 2：若为 Bean 校验异常，解析具体校验不通过的属性字段和默认错误提示
    if (exception instanceof MethodArgumentNotValidException validation
        && validation.getBindingResult().getFieldError() != null) {
      String field = validation.getBindingResult().getFieldError().getField();
      String fieldMessage = validation.getBindingResult().getFieldError().getDefaultMessage();
      if (fieldMessage != null && !fieldMessage.isBlank()) {
        message = fieldMessage;
      }
      LOGGER.warn("文件请求参数校验失败: field={}, message={}", field, message);
    } else {
      // 步骤 3：常规非法参数异常记录异常类型及信息
      LOGGER.warn("文件请求参数不合法: type={}, detail={}", exception.getClass().getSimpleName(), exception.getMessage());
    }

    // 步骤 4：返回统一 400 状态码与响应体
    return ResponseEntity.badRequest().body(new ApiResponse<>(400, message, null));
  }

  /** 记录未知异常类别与堆栈，对客户端返回统一脱敏 500。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
    LOGGER.error("文件接口发生未预期系统异常，type={}", exception.getClass().getSimpleName(), exception);
    return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "服务器内部错误", null));
  }
}
