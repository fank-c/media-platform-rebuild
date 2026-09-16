package com.calles.platform.audit.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.audit.infrastructure.persistence.entity.AuditSensitiveWordPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词字典 MyBatis-Plus Mapper 接口。
 *
 * <p>职责：定义针对表 {@code audit_sensitive_word} 的底层 CRUD 数据库访问契约，继承 MyBatis-Plus 的 {@link BaseMapper}。</p>
 */
@Mapper
public interface AuditSensitiveWordMapper extends BaseMapper<AuditSensitiveWordPO> {
}
