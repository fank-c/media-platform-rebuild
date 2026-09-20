package com.calles.platform.audit.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.enums.CallbackStatus;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import com.calles.platform.audit.infrastructure.persistence.entity.AuditTaskPO;
import com.calles.platform.audit.infrastructure.persistence.mapper.AuditTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 审核任务仓储实现类 (AuditTaskRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施层针对审核任务聚合根 {@link AuditTask} 的持久化适配器；</li>
 *   <li><b>协作对象</b>：委托 {@link AuditTaskMapper} 执行 MySQL {@code audit_task} 表的数据读写；</li>
 *   <li><b>职责限定</b>：负责 PO 与领域实体的互转，不包含业务判断。</li>
 * </ul>
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class AuditTaskRepositoryImpl implements AuditTaskRepository {

    /** 审核任务数据访问 Mapper。 */
    private final AuditTaskMapper auditTaskMapper;

    @Override
    public int insert(AuditTask task) {
        // 步骤 1：将领域模型转换为持久化 PO 实体
        AuditTaskPO po = AuditTaskPO.fromDomain(task);
        // 步骤 2：执行底层数据库插入
        return auditTaskMapper.insert(po);
    }

    @Override
    public int updateById(AuditTask task) {
        // 步骤 1：将领域实体转换为持久化 PO
        AuditTaskPO po = AuditTaskPO.fromDomain(task);
        // 步骤 2：依据主键更新行记录
        return auditTaskMapper.updateById(po);
    }

    @Override
    public Optional<AuditTask> findById(String id) {
        // 步骤 1：查询数据库持久化 PO
        AuditTaskPO po = auditTaskMapper.selectById(id);
        // 步骤 2：反序列化转换为领域实体
        return Optional.ofNullable(po).map(AuditTaskPO::toDomain);
    }

    @Override
    public Optional<AuditTask> findLatestByBiz(String bizType, String bizId) {
        // 步骤 1：构造条件：biz_type + biz_id，按创建时间倒序排
        LambdaQueryWrapper<AuditTaskPO> wrapper = new LambdaQueryWrapper<AuditTaskPO>()
                .eq(AuditTaskPO::getBizType, bizType)
                .eq(AuditTaskPO::getBizId, bizId)
                .orderByDesc(AuditTaskPO::getCreatedAt)
                .last("LIMIT 1");
        // 步骤 2：检索最近一条记录
        AuditTaskPO po = auditTaskMapper.selectOne(wrapper);
        return Optional.ofNullable(po).map(AuditTaskPO::toDomain);
    }

    @Override
    public List<AuditTask> findPendingCallbacks(CallbackStatus status, int maxRetries, int limit) {
        // 步骤 1：查询待补偿重试的 PO 实体列表
        List<AuditTaskPO> list = auditTaskMapper.selectPendingCallbacks(status.getCode(), maxRetries, limit);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        // 步骤 2：批量映射为领域实体列表
        return list.stream().map(AuditTaskPO::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<AuditTask> findRunningMachineAuditTasks(int limit) {
        // 步骤 1：构造条件，精准命中 idx_audit_stage_result 复合索引
        LambdaQueryWrapper<AuditTaskPO> wrapper = new LambdaQueryWrapper<AuditTaskPO>()
                .eq(AuditTaskPO::getStage, AuditStage.MACHINE_AUDITING.name())
                .orderByAsc(AuditTaskPO::getCreatedAt)
                .last("LIMIT " + Math.max(1, limit));

        // 步骤 2：执行检索并转换为领域模型
        List<AuditTaskPO> list = auditTaskMapper.selectList(wrapper);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(AuditTaskPO::toDomain).collect(Collectors.toList());
    }
}
