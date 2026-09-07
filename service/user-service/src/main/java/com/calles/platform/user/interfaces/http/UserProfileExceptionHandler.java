package com.calles.platform.user.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.user.exception.UserProfileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * user-service 范围的异常协议适配器，不改变 auth 等其他服务的错误语义。
 */
@RestControllerAdvice(basePackages = "com.calles.platform.user.interfaces.http")
public class UserProfileExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserProfileExceptionHandler.class);

    /** 将可预期资料异常映射为真实 HTTP 状态和统一响应结构。 */
    @ExceptionHandler(UserProfileException.class)
    public ResponseEntity<ApiResponse<Void>> handleProfile(UserProfileException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiResponse<>(exception.getStatus().value(), exception.getMessage(), null));
    }

    /** 将 JSON 类型和日期解析失败映射为 400，不暴露 Jackson 内部细节。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请求体格式不合法", null));
    }

    /** 记录未预期异常类型并返回脱敏 500。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        LOGGER.error("用户资料接口发生未预期异常，type={}", exception.getClass().getSimpleName(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>(500, "服务器内部错误", null));
    }
}
