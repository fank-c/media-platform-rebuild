package com.calles.platform.interaction.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 互动服务业务异常。
 */
@Getter
public class InteractionException extends RuntimeException {

    private final HttpStatus status;
    private final Object data;

    public InteractionException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public InteractionException(HttpStatus status, String message, Object data) {
        super(message);
        this.status = status;
        this.data = data;
    }
}
