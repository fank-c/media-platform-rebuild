package com.calles.platform.audit.application.executor;

import com.calles.platform.audit.application.executor.model.AuditBizType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 审核执行器路由派发组件。
 *
 * <p>基于 Spring 自动注入所有 {@link AuditExecutor} 策略实现，并在内存中构建高效的 {@link EnumMap} 路由分发表，
 * 支持基于强类型枚举 {@link AuditBizType} 进行 O(1) 复杂度的精准派发，同时兼容历史字符串入参。</p>
 */
@Slf4j
@Component
public class AuditExecutorRouter {

    /** 基于业务类型枚举映射的高速路由表。 */
    private final Map<AuditBizType, AuditExecutor> executorMap = new EnumMap<>(AuditBizType.class);

    public AuditExecutorRouter(List<AuditExecutor> executors) {
        if (executors != null) {
            for (AuditExecutor executor : executors) {
                if (executor.getBizType() != null) {
                    executorMap.put(executor.getBizType(), executor);
                }
            }
        }
    }

    /**
     * 根据强类型业务枚举查找对应的审核执行器。
     *
     * @param bizType 业务类型枚举
     * @return 匹配的审核执行器
     * @throws IllegalArgumentException 若未找到对应的执行器
     */
    public AuditExecutor route(AuditBizType bizType) {
        if (bizType == null) {
            throw new IllegalArgumentException("审核业务类型不能为空");
        }
        AuditExecutor executor = executorMap.get(bizType);
        if (executor == null) {
            log.error("未找到支持业务类型 [{}] 的审核执行器", bizType);
            throw new IllegalArgumentException("不支持的审核业务类型: " + bizType.getCode());
        }
        return executor;
    }

    /**
     * 根据业务类型编码字符串查找对应的审核执行器（向下平滑兼容）。
     *
     * @param bizTypeCode 业务类型标识（如 "VIDEO", "COMMENT"）
     * @return 匹配的审核执行器
     * @throws IllegalArgumentException 若编码非法或未找到对应的执行器
     */
    public AuditExecutor route(String bizTypeCode) {
        AuditBizType type = AuditBizType.of(bizTypeCode);
        if (type == null) {
            log.error("未知的审核业务类型编码: [{}]", bizTypeCode);
            throw new IllegalArgumentException("未知的审核业务类型: " + bizTypeCode);
        }
        return route(type);
    }
}
