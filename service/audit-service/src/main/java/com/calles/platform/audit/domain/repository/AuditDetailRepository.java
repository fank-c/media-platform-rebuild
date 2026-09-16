package com.calles.platform.audit.domain.repository;

import com.calles.platform.audit.domain.model.AuditDetail;

import java.util.List;

/**
 * 审核明细仓储接口。
 *
 * <p>职责定义：负责单次审核任务所衍生的各维度判定明细（文本、封面、视频）的持久化与历史回溯查询。</p>
 * <p>所属边界：审核服务领域仓储层，仅面向 {@link AuditDetail} 实体，不承担任务状态机流转或外部调用编排。</p>
 */
public interface AuditDetailRepository {

    /**
     * 单条插入审核判定明细。
     *
     * @param detail 待持久化的审核明细领域实体（要求已包含任务 ID 与审查维度）
     * @return 数据库受影响行数（1 为成功）
     */
    int insert(AuditDetail detail);

    /**
     * 批量持久化审核维度明细集合。
     *
     * @param details 审核明细列表（可为空，为空时直接返回 0，具备幂等无害性）
     * @return 累计插入成功行数
     */
    int insertBatch(List<AuditDetail> details);

    /**
     * 根据审核任务主键 ID 查询关联的全部审查维度判定结果（按记录创建时间升序排列）。
     *
     * @param taskId 审核任务主键 ID（不能为空）
     * @return 审查明细列表；若无明细返回空集合而非 null
     */
    List<AuditDetail> findByTaskId(String taskId);
}
