package com.calles.platform.transcode.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 转码业务统一异常。
 */
@Getter
public class TranscodeException extends RuntimeException {

    private final HttpStatus status;

    public TranscodeException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public TranscodeException(String message) {
        super(message);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public TranscodeException(String message, Throwable cause) {
        super(message, cause);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public TranscodeException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public static TranscodeException badRequest(String message) {
        return new TranscodeException(HttpStatus.BAD_REQUEST, message);
    }

    public static TranscodeException notFound(String message) {
        return new TranscodeException(HttpStatus.NOT_FOUND, message);
    }

    public static TranscodeException internal(String message, Throwable cause) {
        return new TranscodeException(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }
}
