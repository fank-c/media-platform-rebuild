package com.calles.platform.user.exception;

/**
 * 账号创建消息不满足已支持 v1 协议时抛出的不可重试异常。
 *
 * <p>该异常由 RabbitMQ 入站适配器转换为不重新入队的拒绝结果；数据库和资料初始化等临时故障
 * 不应使用此异常，以便监听容器仍可按既有配置有限重试。</p>
 */
public class InvalidAccountCreatedEventException extends RuntimeException {

    /**
     * 创建仅含协议错误说明的异常。
     *
     * @param message 面向受控日志的非敏感错误说明
     */
    public InvalidAccountCreatedEventException(String message) {
        super(message);
    }

    /**
     * 创建保留底层解析原因的协议异常。
     *
     * @param message 面向受控日志的非敏感错误说明
     * @param cause JSON 解析或字段转换失败原因
     */
    public InvalidAccountCreatedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
