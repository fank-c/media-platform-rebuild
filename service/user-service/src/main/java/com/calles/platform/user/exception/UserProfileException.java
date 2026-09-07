package com.calles.platform.user.exception;

import org.springframework.http.HttpStatus;

/**
 * 用户资料可预期业务异常，携带真实 HTTP 状态但不暴露数据库实现详情。
 */
public class UserProfileException extends RuntimeException {

    /** 对外 HTTP 状态。 */
    private final HttpStatus status;

    /** 创建资料业务异常。 */
    public UserProfileException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** 返回应写入响应的 HTTP 状态。 */
    public HttpStatus getStatus() { return status; }
}
