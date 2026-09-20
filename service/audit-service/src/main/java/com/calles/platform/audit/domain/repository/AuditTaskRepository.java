package com.calles.platform.audit.domain.repository;

import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.CallbackStatus;

import java.util.List;
import java.util.Optional;

/**
 * 审核任务仓储端口接口 (AuditTaskRepository)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：领域层驱动端口 (Driven Port)，定义对 {@link AuditTask} 聚合根实体的持久化访问契约；</li>
 *   <li><b>协作对象</b>：由基础设施层持久化仓储实现类进行具体 MyBatis-Plus 适配；</li>
 *   <li><b>不应承担的工作</b>：仅声明数据存储契约，不包含状态机流转决策或外部调用。</li>
 * </ul>
 * </p>
 */
public interface AuditTaskRepository {

    /**
     * 新增审核任务聚合根持久化记录。
     *
     * @param task 审核任务实体，不可为 null
     * @return 数据库受影响行数 (通常为 1)
     */
    int insert(AuditTask task);

    /**
     * 根据主键 ID 更新审核任务聚合根状态与时间戳。
     *
     * <p><b>幂等性说明</b>：依据主键更新，重复执行结果一致。</p>
     *
     * @param task 包含最新状态的审核任务实体
     * @return 数据库受影响行数
     */
    int updateById(AuditTask task);

    /**
     * 根据主键物理 ID 检索审核任务。
     *
     * @param id 任务主键 ID (UUID 32位)
     * @return 审核任务 Optional 包装，未命中时返回 empty
     */
    Optional<AuditTask> findById(String id);

    /**
     * 根据业务类型与业务内部 ID 查询最新的审核任务（用于提审幂等判定）。
     *
     * @param bizType 业务归属类型 (如 VIDEO)
     * @param bizId 业务主键 ID (如 videoId)
     * @return 最近一条审核任务 Optional
     */
    Optional<AuditTask> findLatestByBiz(String bizType, String bizId);

    /**
     * 查询指定回调状态且重试次数小于上限的待补偿任务列表（供定时调度器补偿重试）。
     *
     * @param status 回调状态 (通常为 FAILED)
     * @param maxRetries 最大重试上限阈值
     * @param limit 单批次拉取数量上限
     * @return 待重试任务列表，未命中返回空列表
     */
    List<AuditTask> findPendingCallbacks(CallbackStatus status, int maxRetries, int limit);

    /**
     * 查询当前处于机审中 (MACHINE_AUDITING) 阶段的任务列表（供定时对账扫描器补查与超时判定）。
     *
     * @param limit 单批次最大拉取记录数
     * @return 机审进行中的任务列表，未命中返回空列表
     */
    List<AuditTask> findRunningMachineAuditTasks(int limit);
}
