package com.calles.platform.transcode.interfaces.http.advice;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.transcode.exception.TranscodeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 视频转码微服务统一 HTTP 异常拦截与协议映射处理器 (TranscodeExceptionHandler)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：位于 Web 入站适配层，统一拦截 Controller 抛出的业务受控异常与未预期系统异常；</li>
 *   <li><b>协作契约</b>：格式化为统一的 {@link ApiResponse} 结构输出；</li>
 *   <li><b>安全与可观测性</b>：精确记录告警级别日志，隐藏敏感内部堆栈与 SQL 细节。</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.calles.platform.transcode.interfaces.http")
public class TranscodeExceptionHandler {

    /**
     * 处理转码业务受控异常。
     */
    @ExceptionHandler(TranscodeException.class)
    public ResponseEntity<ApiResponse<Void>> handleTranscodeException(TranscodeException exception) {
        log.warn("转码业务受控异常: status={}, message={}", exception.getStatus().value(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiResponse<>(exception.getStatus().value(), exception.getMessage(), null));
    }

    /**
     * 处理参数校验或非法状态机跳转异常。
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentOrState(RuntimeException exception) {
        log.warn("业务状态或参数校验不通过: message={}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>(HttpStatus.BAD_REQUEST.value(), exception.getMessage(), null));
    }

    /**
     * 处理 Bean Validation 请求参数校验失败异常。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("HTTP 请求参数校验失败: errors=[{}]", detail);
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>(HttpStatus.BAD_REQUEST.value(), detail, null));
    }

    /**
     * 处理 HTTP 请求体解析与反序列化失败异常。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException exception) {
        log.warn("HTTP 请求体反序列化失败: message={}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>(HttpStatus.BAD_REQUEST.value(), "请求体格式错误或缺少必填字段", null));
    }

    /**
     * 兜底拦截未预期的未知系统级异常。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnhandledException(Exception exception) {
        log.error("转码服务未捕获严重系统级异常: error={}", exception.getMessage(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>(HttpStatus.INTERNAL_SERVER_ERROR.value(), "转码微服务内部繁忙，请稍后重试", null));
    }
}
