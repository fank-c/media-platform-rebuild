package com.calles.platform.auth.interfaces.http;

import com.calles.platform.auth.exception.AuthException;
import com.calles.platform.common.core.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;

/**
 * 将认证错误映射为稳定的 HTTP 状态和 ApiResponse，避免向客户端暴露底层数据库、Redis 或 JWT 异常。
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    /** 仅记录意外异常的完整堆栈；认证域异常消息可安全返回客户端。 */
    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    /**
     * 将已分类的认证错误映射为既定 HTTP 状态。错误码由统一响应的状态码和消息契约承载，
     * 不透传底层异常链。
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthException(AuthException ex) {
        return response(ex.getStatus(), ex.getMessage());
    }

    /**
     * 统一处理 HTTP 协议和 Bean Validation 错误。仅在字段校验失败时返回已声明的字段提示，
     * JSON 解析和 Header 错误保持通用消息，避免暴露请求解析细节。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception ex) {
        String message = "请求参数错误";
        if (ex instanceof MethodArgumentNotValidException validation
                && validation.getBindingResult().getFieldError() != null) {
            String fieldMessage = validation.getBindingResult().getFieldError().getDefaultMessage();
            if (fieldMessage != null && !fieldMessage.isBlank()) {
                message = fieldMessage;
            }
        }
        return response(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 未预期错误只写服务端日志，对客户端返回通用不可用信息，防止泄露 JWT、数据库或 Redis 细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("认证服务处理请求失败", ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "认证服务暂不可用");
    }

    /** 构造统一空数据错误响应，保持 HTTP 状态与响应体 code 一致。 */
    private ResponseEntity<ApiResponse<Void>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ApiResponse<>(status.value(), message, null));
    }
}
