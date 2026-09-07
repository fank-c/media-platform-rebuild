package com.calles.platform.common.core;

/**
 * 对外 REST 接口成功响应的基础结构。
 *
 * <p>该类型只承载跨服务通用的响应字段，不应放入具体领域的状态或数据结构。</p>
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }
}
