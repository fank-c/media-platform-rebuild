package com.calles.platform.interaction.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 互动服务业务异常。
 */
@Getter
public class InteractionException extends RuntimeException {

    private final HttpStatus status;

    public InteractionException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
