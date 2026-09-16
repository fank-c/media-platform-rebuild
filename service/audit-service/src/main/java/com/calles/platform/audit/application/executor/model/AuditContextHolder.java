package com.calles.platform.audit.application.executor.model;

/**
 * 审核执行上下文线程局部持有器 (AuditContextHolder)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核应用层执行器模型，封装当前机审线程的 {@link AuditContext} 上下文隐式传递；</li>
 *   <li><b>协作对象</b>：供 {@code VideoAuditExecutor} 设置，供阿里云机审等特定引擎按需读取额外提审元数据 (authorId, taskId)；</li>
 *   <li><b>防腐隔离</b>：避免在顶层 {@code ImageAuditEngine} / {@code VideoAuditEngine} 契约上强行增加业务关联参数破坏通用性。</li>
 * </ul>
 * </p>
 */
public final class AuditContextHolder {

    private static final ThreadLocal<AuditContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private AuditContextHolder() {
    }

    /**
     * 设置当前线程关联的审核上下文。
     *
     * @param context 审核执行上下文
     */
    public static void set(AuditContext context) {
        CONTEXT_HOLDER.set(context);
    }

    /**
     * 获取当前线程关联的审核上下文。
     *
     * @return 审核执行上下文，若未设置返回 null
     */
    public static AuditContext get() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 清理当前线程的上下文信息，防止内存泄漏或线程池污染。
     */
    public static void clear() {
        CONTEXT_HOLDER.remove();
    }
}
