package com.calles.platform.recommend.interfaces.web;

import com.calles.platform.common.core.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 推荐模块 HTTP 异常适配器；使用统一外壳，错误提示不回显请求或内部异常细节。 */
@Slf4j
@RestControllerAdvice(basePackageClasses = RecommendFeedController.class)
public class RecommendExceptionHandler {
    /**
     * 缺少身份独立返回 401，不能将一般参数错误视为未登录。
     * @param exception 控制器身份检查异常
     * @return 401 统一响应
     */
    @ExceptionHandler(MissingIdentityException.class)
    public ResponseEntity<ApiResponse<Void>> handleIdentity(MissingIdentityException exception) {
        return ResponseEntity.status(401).body(new ApiResponse<>(401, exception.getMessage(), null));
    }

    /**
     * 非法业务参数只返回安全的通用提示，避免回显任意枚举输入。
     * @param exception 参数解析或业务校验异常
     * @return 400 统一响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请求参数不合法", null));
    }

    /**
     * 请求体字段校验失败不返回被拒绝的值或原始对象。
     * @param exception Bean Validation 校验异常
     * @return 400 统一响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请求参数不合法", null));
    }

    /**
     * JSON 格式或字段类型错误保留客户端错误语义。
     * @param exception 请求体解析异常
     * @return 400 统一响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请求体格式不合法", null));
    }

    /**
     * 查询参数无法转换为声明类型时保留 400，而不是落入系统错误。
     * @param exception 参数类型转换异常
     * @return 400 统一响应
     */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(TypeMismatchException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, "请求参数类型不合法", null));
    }

    /**
     * 框架协议异常保留原状态与响应头；未知异常仅对外返回 500 通用提示。
     * @param exception 未被专门处理的异常
     * @return 协议状态或 500 统一响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        // 保留框架定义的 HTTP 状态及 Accept、Allow 等协议响应头。
        if (exception instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            return ResponseEntity.status(status).headers(error.getHeaders())
                    .body(new ApiResponse<>(status, "请求无法处理", null));
        }
        // 不记录异常消息或请求内容，避免底层异常夹带凭据及用户数据。
        log.error("推荐接口发生未预期异常: type={}", exception.getClass().getName());
        return ResponseEntity.internalServerError().body(new ApiResponse<>(500, "服务器内部错误", null));
    }
}
