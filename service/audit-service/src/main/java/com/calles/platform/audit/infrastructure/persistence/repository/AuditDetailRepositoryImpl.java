package com.calles.platform.audit.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.repository.AuditDetailRepository;
import com.calles.platform.audit.infrastructure.persistence.entity.AuditDetailPO;
import com.calles.platform.audit.infrastructure.persistence.mapper.AuditDetailMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 审核明细仓储基于 MyBatis-Plus 的实现类。
 *
 * <p>职责：封装持久化对象 {@link AuditDetailPO} 与领域实体 {@link AuditDetail} 之间的相互转换，
 * 通过 {@link AuditDetailMapper} 执行底层 MySQL 读写操作。</p>
 */
@Repository
@RequiredArgsConstructor
public class AuditDetailRepositoryImpl implements AuditDetailRepository {

    /** 审核明细持久化 Mapper 接口。 */
    private final AuditDetailMapper auditDetailMapper;

    @Override
    public int insert(AuditDetail detail) {
        // 步骤 1：将领域模型转换为持久化实体 PO
        AuditDetailPO po = AuditDetailPO.fromDomain(detail);
        // 步骤 2：执行底层数据库插入
        return auditDetailMapper.insert(po);
    }

    @Override
    public int insertBatch(List<AuditDetail> details) {
        // 步骤 1：防御性非空检查，空列表直接返回 0
        if (details == null || details.isEmpty()) {
            return 0;
        }
        // 步骤 2：逐条转换并执行批量持久化
        int count = 0;
        for (AuditDetail detail : details) {
            count += insert(detail);
        }
        return count;
    }

    @Override
    public List<AuditDetail> findByTaskId(String taskId) {
        // 步骤 1：构造基于任务 ID 的升序查询条件包装器
        LambdaQueryWrapper<AuditDetailPO> wrapper = new LambdaQueryWrapper<AuditDetailPO>()
                .eq(AuditDetailPO::getTaskId, taskId)
                .orderByAsc(AuditDetailPO::getCreatedAt);
        // 步骤 2：执行数据库查询
        List<AuditDetailPO> list = auditDetailMapper.selectList(wrapper);
        // 步骤 3：若为空则返回空集合，避免上层发生空指针异常
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        // 步骤 4：将持久化 PO 列表转换为领域模型实体列表
        return list.stream().map(AuditDetailPO::toDomain).collect(Collectors.toList());
    }
}
