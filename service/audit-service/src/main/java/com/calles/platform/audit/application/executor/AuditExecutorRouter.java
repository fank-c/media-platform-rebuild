package com.calles.platform.audit.application.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审核执行器路由派发组件。
 *
 * <p>基于 Spring 自动注入所有 {@link AuditExecutor} 实现，并按业务类型自动匹配路由。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditExecutorRouter {

    private final List<AuditExecutor> executors;

    /**
     * 根据业务类型查找对应的审核执行器。
     *
     * @param bizType 业务类型标识（如 "VIDEO"）
     * @return 匹配的审核执行器
     * @throws IllegalArgumentException 若未找到对应的执行器
     */
    public AuditExecutor route(String bizType) {
        if (executors != null) {
            for (AuditExecutor executor : executors) {
                if (executor.supports(bizType)) {
                    return executor;
                }
            }
        }
        log.error("未找到支持业务类型 [{}] 的审核执行器", bizType);
        throw new IllegalArgumentException("不支持的审核业务类型: " + bizType);
    }
}
