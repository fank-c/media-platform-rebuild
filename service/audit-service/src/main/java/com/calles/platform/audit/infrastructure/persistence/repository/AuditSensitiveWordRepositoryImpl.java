package com.calles.platform.audit.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.audit.domain.model.AuditSensitiveWord;
import com.calles.platform.audit.domain.model.enums.CommonStatus;
import com.calles.platform.audit.domain.repository.AuditSensitiveWordRepository;
import com.calles.platform.audit.infrastructure.persistence.entity.AuditSensitiveWordPO;
import com.calles.platform.audit.infrastructure.persistence.mapper.AuditSensitiveWordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 敏感词字典仓储基于 MyBatis-Plus 的实现类。
 *
 * <p>职责：封装敏感词持久化对象 {@link AuditSensitiveWordPO} 与领域实体 {@link AuditSensitiveWord} 的转换，
 * 负责词库的底层数据库交互。</p>
 */
@Repository
@RequiredArgsConstructor
public class AuditSensitiveWordRepositoryImpl implements AuditSensitiveWordRepository {

    /** 敏感词数据访问 Mapper 接口。 */
    private final AuditSensitiveWordMapper sensitiveWordMapper;

    @Override
    public int insert(AuditSensitiveWord word) {
        // 步骤 1：将敏感词领域实体转换为持久化 PO
        AuditSensitiveWordPO po = AuditSensitiveWordPO.fromDomain(word);
        // 步骤 2：执行数据库插入
        return sensitiveWordMapper.insert(po);
    }

    @Override
    public List<AuditSensitiveWord> findByStatus(CommonStatus status) {
        // 步骤 1：根据生效状态构建条件包装器
        LambdaQueryWrapper<AuditSensitiveWordPO> wrapper = new LambdaQueryWrapper<AuditSensitiveWordPO>()
                .eq(AuditSensitiveWordPO::getStatus, status.name());
        // 步骤 2：执行数据库条件查询
        List<AuditSensitiveWordPO> list = sensitiveWordMapper.selectList(wrapper);
        // 步骤 3：若记录为空直接返回空列表
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        // 步骤 4：转换为领域模型实体列表返回
        return list.stream().map(AuditSensitiveWordPO::toDomain).collect(Collectors.toList());
    }

    @Override
    public Optional<AuditSensitiveWord> findByWord(String word) {
        // 步骤 1：根据词条文本构建精确查找包装器
        LambdaQueryWrapper<AuditSensitiveWordPO> wrapper = new LambdaQueryWrapper<AuditSensitiveWordPO>()
                .eq(AuditSensitiveWordPO::getWord, word);
        // 步骤 2：执行单条记录查询
        AuditSensitiveWordPO po = sensitiveWordMapper.selectOne(wrapper);
        // 步骤 3：将查询结果转换为 Optional 包装的领域实体
        return Optional.ofNullable(po).map(AuditSensitiveWordPO::toDomain);
    }

    @Override
    public int deleteById(String id) {
        // 步骤 1：依据主键 ID 执行数据库物理删除
        return sensitiveWordMapper.deleteById(id);
    }
}
