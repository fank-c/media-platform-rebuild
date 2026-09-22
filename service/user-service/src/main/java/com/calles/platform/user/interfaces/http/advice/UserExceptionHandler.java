package com.calles.platform.user.interfaces.http.advice;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.user.exception.UserProfileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * user-service 范围的异常协议适配器，不改变 auth 等其他服务的错误语义。
 */
@RestControllerAdvice(basePackages = "com.calles.platform.user.interfaces.http")
public class UserExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserExceptionHandler.class);

    /** 将可预期资料异常映射为真实 HTTP 状态和统一响应结构。 */
    @ExceptionHandler(UserProfileException.class)
    public ResponseEntity<ApiResponse<Void>> handleProfile(UserProfileException exception) {
        LOGGER.warn("用户资料业务异常: status={}, message={}", exception.getStatus().value(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiResponse<>(exception.getStatus().value(), exception.getMessage(), null));
    }

    /** 将 JSON 类型和日期解析失败映射为 400，不暴露 Jackson 内部细节。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        LOGGER.warn("请求体反序列化失败: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请求体格式不合法", null));
    }

    /** 将参数校验失败映射为 400 并记录具体字段原因。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        // 步骤 1：准备默认错误消息
        String message = "请求参数错误";

        // 步骤 2：若包含字段校验错误，提取出错字段名称与具体校验规则提示
        if (exception.getBindingResult().getFieldError() != null) {
            String field = exception.getBindingResult().getFieldError().getField();
            String fieldMessage = exception.getBindingResult().getFieldError().getDefaultMessage();
            if (fieldMessage != null && !fieldMessage.isBlank()) {
                message = fieldMessage;
            }
            logWarnFieldValidation(field, message);
        }

        // 步骤 3：返回统一 400 错误响应
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, message, null));
    }

    private void logWarnFieldValidation(String field, String message) {
        LOGGER.warn("用户资料请求参数校验失败: field={}, message={}", field, message);
    }

    /** 记录未预期异常类型并返回脱敏 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        LOGGER.error("用户资料接口发生未预期异常，type={}", exception.getClass().getSimpleName(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>(500, "服务器内部错误", null));
    }
}
