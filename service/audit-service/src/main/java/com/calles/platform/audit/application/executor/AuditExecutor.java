package com.calles.platform.audit.application.executor;

import com.calles.platform.audit.application.executor.model.AuditBizType;
import com.calles.platform.audit.application.executor.model.AuditContext;
import com.calles.platform.audit.application.executor.model.AuditExecutionResult;

/**
 * 业务专属审核执行器策略接口。
 *
 * <p>不同业务类型（如 VIDEO、COMMENT、AVATAR）实现本接口，声明绑定的业务枚举，并定义专属的多维度机审流程与裁决规则。</p>
 */
public interface AuditExecutor {

    /**
     * 声明当前执行器绑定的业务类型枚举。
     *
     * @return 业务类型枚举
     */
    AuditBizType getBizType();

    /**
     * 判断当前执行器是否支持处理指定的业务类型枚举。
     *
     * @param bizType 业务类型枚举
     * @return true 表示支持
     */
    default boolean supports(AuditBizType bizType) {
        return getBizType() == bizType;
    }

    /**
     * 判断当前执行器是否支持处理指定的业务类型编码（平滑兼容字符串调用）。
     *
     * @param bizType 业务类型标识（如 "VIDEO", "COMMENT"）
     * @return true 表示支持
     */
    default boolean supports(String bizType) {
        return getBizType() != null && getBizType().matches(bizType);
    }

    /**
     * 执行具体的合规审查流水线。
     *
     * @param context 审核上下文
     * @return 综合审核执行判定结果
     */
    AuditExecutionResult execute(AuditContext context);
}
