package com.calles.platform.interaction.exception;

import org.springframework.http.HttpStatus;

/**
 * 观看会话已超时过期业务异常 (409 CONFLICT)。
 *
 * <p>职责与约束：当起播重试或后续心跳命中的会话已超时关闭、或超出保留期时抛出。
 * 客户端收到该异常后，若用户仍在播放，应重新发起新的起播请求。</p>
 */
public class WatchSessionExpiredException extends InteractionException {

    /**
     * 构造会话过期异常。
     *
     * @param message 异常提示信息
     */
    public WatchSessionExpiredException(String message) {
        super(HttpStatus.CONFLICT, message != null ? message : "观看会话已超时过期，请重新起播");
    }
}
