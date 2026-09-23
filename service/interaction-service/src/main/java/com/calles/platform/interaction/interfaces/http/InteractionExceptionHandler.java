package com.calles.platform.interaction.interfaces.http;

import com.calles.platform.common.core.ApiResponse;
import com.calles.platform.interaction.exception.InteractionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 交互服务全局异常日志与 HTTP 错误响应适配器。
 *
 * <p>业务错误输出可定位的状态和原因；未知系统错误保留服务端堆栈，不向客户端泄漏内部细节。
 * 日志由请求过滤器的 MDC 自动关联 traceId；本处理器不主动采集请求正文或认证凭据。</p>
 */
@Slf4j
@RestControllerAdvice
public class InteractionExceptionHandler {

    /**
     * 记录预期内的交互业务错误并返回其原有状态和提示。
     *
     * @param exception 交互业务异常，包含状态码与面向客户端的提示
     * @return 状态码与异常一致的错误响应
     */
    @ExceptionHandler(InteractionException.class)
    public ResponseEntity<ApiResponse<Void>> handleInteraction(InteractionException exception) {
        // 步骤 1：业务错误只记录可定位的状态与安全提示，不将预期内的未登录当成系统故障输出堆栈。
        log.warn("交互业务异常: status={}, message={}", exception.getStatus().value(), exception.getMessage());
        // 步骤 2：继续使用原有业务状态码和提示，便于客户端区分未登录与系统错误。
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiResponse<>(exception.getStatus().value(), exception.getMessage(), null));
    }

    /**
     * 记录参数校验失败字段与规则，避免输出可能包含敏感值的请求对象。
     *
     * @param exception Spring 参数校验异常
     * @return 不包含原始输入的 400 响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        // 步骤 1：只记录失败字段和校验规则，不打印被拒绝的字段值或请求正文。
        String field = exception.getBindingResult().getFieldError() == null
                ? "unknown" : exception.getBindingResult().getFieldError().getField();
        String rule = exception.getBindingResult().getFieldError() == null
                ? "unknown" : exception.getBindingResult().getFieldError().getCode();
        log.warn("交互请求参数校验失败: field={}, rule={}", field, rule);
        // 步骤 2：维持框架的 400 语义，避免兜底异常处理将客户端错误误报成 500。
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请求参数不合法", null));
    }

    /**
     * 捕获非法参数异常并返回 400 响应。
     *
     * @param exception 非法参数异常
     * @return 400 错误响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException exception) {
        log.warn("交互请求非法参数: message={}", exception.getMessage());
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, exception.getMessage(), null));
    }

    /**
     * 记录请求正文无法解析的异常类别，不记录可能包含敏感值的原始正文或解析器消息。
     *
     * @param exception HTTP 消息反序列化异常
     * @return 400 请求格式错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        // 步骤 1：记录反序列化失败的异常类别和底层原因类型，不打印含原始输入的解析器消息。
        String causeType = exception.getCause() == null ? "unknown" : exception.getCause().getClass().getSimpleName();
        log.warn("交互请求正文解析失败: type={}, causeType={}", exception.getClass().getSimpleName(), causeType);
        // 步骤 2：保持客户端格式错误的 400 语义，避免落入未知系统错误兜底。
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请求体格式不合法", null));
    }

    /**
     * 记录未预期异常的服务端堆栈；框架自身的 HTTP 协议异常保留其原有状态。
     *
     * @param exception 未被业务异常处理器覆盖的异常
     * @return 协议异常的原状态或脱敏的 500 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        if (exception instanceof ErrorResponse httpError) {
            // 步骤 1：例如请求方法不支持应保留 405；日志不输出原始请求内容。
            HttpStatusCode status = httpError.getStatusCode();
            log.warn("交互接口请求错误: status={}, type={}", status.value(), exception.getClass().getSimpleName());
            return ResponseEntity.status(status)
                    .body(new ApiResponse<>(status.value(), "请求无法处理", null));
        }
        // 步骤 2：未知故障记录异常类型与完整堆栈供排查，响应不泄漏内部异常消息。
        log.error("交互接口发生未预期异常: type={}", exception.getClass().getName(), exception);
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "服务器内部错误", null));
    }
}
