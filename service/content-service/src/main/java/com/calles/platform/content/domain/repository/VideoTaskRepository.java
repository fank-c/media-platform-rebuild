package com.calles.platform.content.domain.repository;

import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 视频异步流水线子任务仓储端口接口 (VideoTaskRepository)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：领域层定义的持久化访问端口 (Driven Port)；</li>
 *   <li><b>主要职责</b>：提供对 {@link VideoTask} 实体的基础 CRUD、批量持久化及超时巡检检索契约；</li>
 *   <li><b>实现对象</b>：由基础设施层持久化仓储实现类进行具体实现；</li>
 *   <li><b>禁止职责</b>：本接口仅声明纯粹的数据存储契约，不包含任何业务决策、状态机跃迁或事务编排。</li>
 * </ul>
 * </p>
 */
public interface VideoTaskRepository {

    /**
     * 持久化新增单条任务记录。
     *
     * <p><b>副作用</b>：将任务实体落库并回填持久化主键。</p>
     *
     * @param task 待持久化的任务领域实体，不可为 null
     */
    void insert(VideoTask task);

    /**
     * 批量持久化新增多条任务记录。
     *
     * <p><b>用途</b>：视频提审初始化时，批量将生成的 5 个初始任务一次性持久化。<br>
     * <b>幂等性说明</b>：空列表时不执行任何数据库写入。</p>
     *
     * @param tasks 待持久化的任务领域实体列表，不可为 null
     */
    void batchInsert(List<VideoTask> tasks);

    /**
     * 根据主键更新任务的状态、进度、重试计数及时间戳信息。
     *
     * <p><b>幂等性说明</b>：幂等更新指定主键的一行记录。<br>
     * <b>副作用</b>：更新数据库行数据与修改时间。</p>
     *
     * @param task 包含最新状态与进度的任务领域实体，不可为 null
     * @return 数据库受影响的行数 (通常为 1；若行已被删除则为 0)
     */
    int updateById(VideoTask task);

    /**
     * 根据主键唯一标识检索子任务。
     *
     * @param id 任务主键 ID (UUID 32位)
     * @return 包含任务领域实体的 {@link Optional}，未找到时返回 {@link Optional#empty()}
     */
    Optional<VideoTask> findById(String id);

    /**
     * 根据所属视频全局内部 ID 检索其名下的全部流水线子任务。
     *
     * @param videoId 关联的视频全局主键 ID
     * @return 匹配的子任务领域实体列表，按创建时间升序排列；若无任务则返回空列表（绝不返回 null）
     */
    List<VideoTask> findByVideoId(String videoId);

    /**
     * 根据视频内部 ID 与任务类型唯一检索指定的子任务。
     *
     * <p>由于业务约束每个视频针对每种 {@link TaskType} 仅存在一个任务记录（由唯一索引保证）。</p>
     *
     * @param videoId 视频内部主键 ID
     * @param taskType 任务类型枚举
     * @return 匹配的任务实体 {@link Optional}，未找到时返回 {@link Optional#empty()}
     */
    Optional<VideoTask> findByVideoIdAndTaskType(String videoId, TaskType taskType);

    /**
     * 扫描超时未完成且处于特定状态的任务集合（用于超时巡检与自愈补偿）。
     *
     * @param status 目标过滤任务状态 (如 {@link TaskStatus#RUNNING})
     * @param startedBefore 启动时间早于该时间戳阈值（即 started_at &lt;= startedBefore）
     * @return 满足超时条件的任务列表；若无超时任务返回空列表
     */
    List<VideoTask> findTimeoutTasks(TaskStatus status, LocalDateTime startedBefore);
}
