package com.calles.platform.audit.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.audit.infrastructure.persistence.entity.AuditTaskPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 审核主任务 MyBatis-Plus Mapper 接口。
 *
 * <p>职责：定义针对表 {@code audit_task} 的底层 CRUD 与特定状态补偿检索契约，继承 MyBatis-Plus 的 {@link BaseMapper}。</p>
 */
@Mapper
public interface AuditTaskMapper extends BaseMapper<AuditTaskPO> {

    /**
     * 查询指定回调状态且重试次数小于上限的待补偿任务列表。
     *
     * @param status 回调状态 (如 FAILED, PENDING)
     * @param maxRetries 最大重试上限
     * @param limit 条数限制
     * @return 审核任务持久化对象列表
     */
    @Select("SELECT * FROM audit_task " +
            "WHERE callback_status = #{status} AND callback_retries < #{maxRetries} " +
            "AND stage = 'FINISHED' " +
            "ORDER BY updated_at ASC LIMIT #{limit}")
    List<AuditTaskPO> selectPendingCallbacks(
            @Param("status") String status,
            @Param("maxRetries") int maxRetries,
            @Param("limit") int limit
    );
}
