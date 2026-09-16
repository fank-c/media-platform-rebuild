package com.calles.platform.audit.interfaces.http.advice;

import com.calles.platform.audit.exception.AuditException;
import com.calles.platform.common.core.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 审核服务统一 HTTP 异常捕获与协议映射处理器。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：位于 Web 接口适配层，负责截获 Controller 抛出的各类型受控或未受控异常；</li>
 *   <li><b>协作对象</b>：与全局统一契约对象 {@link ApiResponse} 协同，保证对网关及客户端输出规整的 JSON 数据结构；</li>
 *   <li><b>安全与可观测性</b>：将异常日志分为 WARN（业务受控异常）和 ERROR（未预期未知异常），杜绝直接向客户端泄露底层 SQL 或堆栈。</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.calles.platform.audit.interfaces.http")
public class AuditExceptionHandler {

    /**
     * 处理审核服务业务受控异常。
     *
     * @param exception 业务异常实例
     * @return 携带对应 HTTP 状态码与业务错误信息的响应实体
     */
    @ExceptionHandler(AuditException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuditException(AuditException exception) {
        // 步骤 1：记录受控业务告警日志
        log.warn("审核服务业务受控异常: status={}, message={}", exception.getStatus().value(), exception.getMessage());
        // 步骤 2：封装标准化契约响应体
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiResponse<>(exception.getStatus().value(), exception.getMessage(), null));
    }

    /**
     * 处理非法参数或非法状态机调用异常。
     *
     * @param exception 运行时非法参数或状态异常
     * @return 400 Bad Request 响应实体
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleStateOrArgument(RuntimeException exception) {
        // 步骤 1：记录参数或状态非法告警日志
        log.warn("业务状态或参数非法: message={}", exception.getMessage());
        // 步骤 2：统一作为 400 错误返回
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>(HttpStatus.BAD_REQUEST.value(), exception.getMessage(), null));
    }

    /**
     * 处理 HTTP 请求体解析与反序列化失败异常。
     *
     * @param exception 请求体格式解析异常
     * @return 400 Bad Request 响应实体
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        // 步骤 1：记录反序列化失败信息
        log.warn("请求体解析失败: {}", exception.getMessage());
        // 步骤 2：返回友好的格式错误提示
        return ResponseEntity.badRequest().body(new ApiResponse<>(HttpStatus.BAD_REQUEST.value(), "请求体格式不合法", null));
    }

    /**
     * 处理 Bean Validation 入参校验失败异常。
     *
     * @param exception 方法参数验证异常
     * @return 400 Bad Request 响应实体，提取首个校验失败字段提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        // 步骤 1：提取具体字段级校验提示
        String message = "请求参数校验失败";
        if (exception.getBindingResult().getFieldError() != null) {
            String fieldMessage = exception.getBindingResult().getFieldError().getDefaultMessage();
            if (fieldMessage != null && !fieldMessage.isBlank()) {
                message = fieldMessage;
            }
            log.warn("请求参数校验未通过: field={}, message={}",
                    exception.getBindingResult().getFieldError().getField(), message);
        }
        // 步骤 2：包装为 400 响应
        return ResponseEntity.badRequest().body(new ApiResponse<>(HttpStatus.BAD_REQUEST.value(), message, null));
    }

    /**
     * 处理系统兜底未预期未知异常。
     *
     * @param exception 未捕获的系统异常
     * @return 500 Internal Server Error 响应实体
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        // 步骤 1：记录完整堆栈用于告警排查
        log.error("审核服务未预期异常: type={}", exception.getClass().getSimpleName(), exception);
        // 步骤 2：安全脱敏返回统一内部错误提示
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务器内部错误", null));
    }
}
