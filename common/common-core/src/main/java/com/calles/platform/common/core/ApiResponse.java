package com.calles.platform.common.core;

public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "ok", data);
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }
}
