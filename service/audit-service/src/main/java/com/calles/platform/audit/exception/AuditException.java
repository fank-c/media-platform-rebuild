package com.calles.platform.audit.exception;

import org.springframework.http.HttpStatus;

/**
 * 审核服务统一业务受控异常。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务内部预期的业务逻辑阻断、非法状态跃迁或校验失败异常；</li>
 *   <li><b>协作对象</b>：由应用服务与领域模型抛出，被 {@link com.calles.platform.audit.interfaces.http.AuditExceptionHandler} 统一捕获；</li>
 *   <li><b>设计考量</b>：携带标准的 HTTP 响应码 (如 400 Bad Request, 404 Not Found, 403 Forbidden, 409 Conflict)，方便 HTTP 适配层转换为契约响应。</li>
 * </ul>
 * </p>
 */
public class AuditException extends RuntimeException {

    /** 对外透出的 HTTP 响应状态枚举。 */
    private final HttpStatus status;

    /**
     * 构造审核服务受控业务异常。
     *
     * @param status HTTP 状态枚举 (如 HttpStatus.BAD_REQUEST)
     * @param message 面向调用方的友好中文错误提示
     */
    public AuditException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 快速构建 404 NOT_FOUND 业务异常。
     *
     * @param message 错误提示
     * @return 业务异常实例
     */
    public static AuditException notFound(String message) {
        return new AuditException(HttpStatus.NOT_FOUND, message);
    }

    /**
     * 快速构建 400 BAD_REQUEST 业务异常。
     *
     * @param message 错误提示
     * @return 业务异常实例
     */
    public static AuditException badRequest(String message) {
        return new AuditException(HttpStatus.BAD_REQUEST, message);
    }

    /**
     * 获取异常关联的 HTTP 状态枚举。
     *
     * @return 对应的 {@link HttpStatus}
     */
    public HttpStatus getStatus() {
        return status;
    }
}
