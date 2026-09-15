package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import com.calles.platform.content.domain.repository.VideoTaskRepository;
import com.calles.platform.content.infrastructure.persistence.entity.VideoTaskPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoTaskMapper;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频异步任务仓储实现类 (VideoTaskRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施层针对子任务实体 {@link VideoTask} 的持久化适配；</li>
 *   <li><b>协作对象</b>：委托 {@link VideoTaskMapper} 访问底层 {@code video_task} 表；</li>
 *   <li><b>特性</b>：负责 PO 与领域实体的互转及批量操作支持。</li>
 * </ul>
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class VideoTaskRepositoryImpl implements VideoTaskRepository {

    /** 任务底层数据访问 Mapper。 */
    private final VideoTaskMapper videoTaskMapper;

    /**
     * 单条持久化插入任务实体。
     *
     * @param task 待插入的领域实体
     */
    @Override
    public void insert(VideoTask task) {
        // 步骤 1：领域实体转换为 PO 持久化实体
        VideoTaskPO po = VideoTaskPO.fromDomain(task);

        // 步骤 2：执行底层数据库插入
        videoTaskMapper.insert(po);

        // 步骤 3：回写持久化生成的主键至领域实体（若有自增或回填）
        if (po.getId() != null) {
            task.setId(po.getId());
        }
    }

    /**
     * 批量持久化插入任务实体列表。
     *
     * @param tasks 待插入的领域任务列表
     */
    @Override
    public void batchInsert(List<VideoTask> tasks) {
        // 步骤 1：防御性空值校验
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        // 步骤 2：逐条持久化（后续数据量大时可按需拓展 MyBatis-Plus 批量扩展）
        for (VideoTask task : tasks) {
            insert(task);
        }
    }

    /**
     * 根据主键更新任务进度与状态。
     *
     * @param task 待更新的领域任务实体
     * @return 数据库受影响行数
     */
    @Override
    public int updateById(VideoTask task) {
        // 步骤 1：领域实体映射为持久化 PO
        VideoTaskPO po = VideoTaskPO.fromDomain(task);

        // 步骤 2：执行依据主键 ID 的动态更新
        return videoTaskMapper.updateById(po);
    }

    /**
     * 根据主键唯一查询任务实体。
     *
     * @param id 任务主键 ID (UUID)
     * @return 领域实体 Optional
     */
    @Override
    public Optional<VideoTask> findById(String id) {
        // 步骤 1：根据主键查询数据库 PO
        VideoTaskPO po = videoTaskMapper.selectById(id);

        // 步骤 2：转换为领域实体并使用 Optional 包装
        return Optional.ofNullable(po).map(VideoTaskPO::toDomain);
    }

    /**
     * 根据视频内部 ID 查找其名下的全部流水线子任务。
     *
     * @param videoId 视频主键 ID
     * @return 领域实体列表
     */
    @Override
    public List<VideoTask> findByVideoId(String videoId) {
        // 步骤 1：根据视频 ID 查询全部 PO 列表
        List<VideoTaskPO> pos = videoTaskMapper.selectByVideoId(videoId);
        if (pos == null) {
            return Collections.emptyList();
        }

        // 步骤 2：Stream 批量映射转换为领域模型列表
        return pos.stream().map(VideoTaskPO::toDomain).toList();
    }

    /**
     * 根据视频 ID 与任务类型唯一查询子任务。
     *
     * @param videoId 视频主键 ID
     * @param taskType 任务类型枚举
     * @return 领域实体 Optional
     */
    @Override
    public Optional<VideoTask> findByVideoIdAndTaskType(String videoId, TaskType taskType) {
        // 步骤 1：空值类型防御
        if (taskType == null) {
            return Optional.empty();
        }

        // 步骤 2：按视频 ID 与任务类型编码精确定位单条记录
        VideoTaskPO po = videoTaskMapper.selectByVideoIdAndTaskType(videoId, taskType.getCode());

        // 步骤 3：映射并返回领域实体
        return Optional.ofNullable(po).map(VideoTaskPO::toDomain);
    }

    /**
     * 查找超时未完成的指定状态任务列表。
     *
     * @param status 目标过滤任务状态
     * @param startedBefore 启动时间早于该时间阈值
     * @return 超时领域任务实体列表
     */
    @Override
    public List<VideoTask> findTimeoutTasks(TaskStatus status, LocalDateTime startedBefore) {
        // 步骤 1：入参有效性防御
        if (status == null || startedBefore == null) {
            return Collections.emptyList();
        }

        // 步骤 2：利用联合索引检索满足超时条件的 PO 列表
        List<VideoTaskPO> pos = videoTaskMapper.selectTimeoutTasks(status.getCode(), startedBefore);
        if (pos == null) {
            return Collections.emptyList();
        }

        // 步骤 3：映射转换为领域实体列表
        return pos.stream().map(VideoTaskPO::toDomain).toList();
    }
}
