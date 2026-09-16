package com.calles.platform.audit.application.executor;

import com.calles.platform.audit.application.executor.model.AuditContext;
import com.calles.platform.audit.application.executor.model.AuditExecutionResult;

/**
 * 业务专属审核执行器策略接口。
 *
 * <p>不同业务类型（如 VIDEO、COMMENT、USER_PROFILE）实现本接口，定义专属的多维度机审流程与裁决规则。</p>
 */
public interface AuditExecutor {

    /**
     * 判断当前执行器是否支持处理指定的业务类型。
     *
     * @param bizType 业务类型标识（如 "VIDEO", "COMMENT"）
     * @return true 表示支持
     */
    boolean supports(String bizType);

    /**
     * 执行具体的合规审查流水线。
     *
     * @param context 审核上下文
     * @return 综合审核执行判定结果
     */
    AuditExecutionResult execute(AuditContext context);
}
