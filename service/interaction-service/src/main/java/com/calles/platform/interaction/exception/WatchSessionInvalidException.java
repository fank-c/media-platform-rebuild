package com.calles.platform.interaction.exception;

import org.springframework.http.HttpStatus;

/**
 * 观看会话无效或不存在业务异常 (409 CONFLICT)。
 *
 * <p>职责与约束：当客户端心跳上报的会话 ID 在数据库中不存在，或属于其他用户/其他视频时抛出。
 * 不泄漏其他用户的敏感标识或内部状态。</p>
 */
public class WatchSessionInvalidException extends InteractionException {

    /**
     * 构造会话无效异常。
     *
     * @param message 异常提示信息
     */
    public WatchSessionInvalidException(String message) {
        super(HttpStatus.CONFLICT, message != null ? message : "观看会话无效或不存在");
    }
}
