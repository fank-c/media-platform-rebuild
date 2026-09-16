package com.calles.platform.content.application.task;

import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.domain.repository.VideoTaskRepository;
import com.calles.platform.content.interfaces.http.dto.VideoResponses;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频异步发布流水线任务协调器 (VideoTaskCoordinator)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：编排提审后并发产生的子任务生命周期与进度交互；</li>
 *   <li><b>协作对象</b>：
 *     <ul>
 *       <li>{@link VideoTaskRepository}：子任务状态与进度持久化；</li>
 *       <li>{@link PublishGatekeeper}：任务成功或失败时驱动分级就绪门禁与驳回处理；</li>
 *       <li>{@link VideoContentRepository}：聚合根状态感知。</li>
 *     </ul>
 *   </li>
 *   <li><b>核心用例</b>：提审时任务网格初始化、外部工作节点执行进度汇报、任务完成/失败推进及聚合进度透出。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoTaskCoordinator {

    /** 视频子任务仓储。 */
    private final VideoTaskRepository videoTaskRepository;

    /** 视频聚合根仓储。 */
    private final VideoContentRepository videoContentRepository;

    /** 分级就绪门禁决策器。 */
    private final PublishGatekeeper publishGatekeeper;

    /**
     * 视频提交审核时初始化生成全量流水线子任务网格。
     *
     * <p><b>幂等性保障</b>：若指定视频已存在子任务（例如重试提审），则跳过重新生成，防止重复插入主键或违反唯一键约束；<br>
     * <b>任务网格</b>：涵盖 AUDIT（内容合规机审）、TRANSCODE_720P（标清）、TRANSCODE_1080P（高清）、TRANSCODE_4K（超高清）与 VECTOR_EMBEDDING（向量检索）。</p>
     *
     * @param videoId 视频全局唯一主键 ID
     */
    @Transactional
    public void initPipelineTasks(String videoId) {
        // 步骤 1：幂等性检查，已初始化任务时直接返回
        List<VideoTask> existing = videoTaskRepository.findByVideoId(videoId);
        if (!existing.isEmpty()) {
            log.info("视频 [{}] 已存在 [{}] 个子任务，复用或跳过重复初始化", videoId, existing.size());
            return;
        }

        // 步骤 2：构建 5 个维度的初始子任务集合 (状态均为 PENDING)
        List<VideoTask> tasks = new ArrayList<>();
        tasks.add(VideoTask.create(videoId, TaskType.AUDIT));
        tasks.add(VideoTask.create(videoId, TaskType.TRANSCODE_720P));
        tasks.add(VideoTask.create(videoId, TaskType.TRANSCODE_1080P));
        tasks.add(VideoTask.create(videoId, TaskType.TRANSCODE_4K));
        tasks.add(VideoTask.create(videoId, TaskType.VECTOR_EMBEDDING));

        // 步骤 3：批量落库持久化
        videoTaskRepository.batchInsert(tasks);
        log.info("成功为视频 [{}] 初始化生成 5 个流水线子任务", videoId);
    }

    /**
     * 视频重新提交审核时复苏流水线子任务网格。
     *
     * <p>若视频子任务已存在，将处于 {@link TaskStatus#FAILED} 或 {@link TaskStatus#CANCELED} 状态的历史任务重置为 {@link TaskStatus#PENDING}，清空失败原因，
     * 确保本次审核通过后各转码子任务与就绪门禁能够重新触发；若尚未初始化任务，则走初始构建逻辑。</p>
     *
     * @param videoId 视频全局唯一主键 ID
     */
    @Transactional
    public void resetPipelineTasksForResubmit(String videoId) {
        List<VideoTask> existing = videoTaskRepository.findByVideoId(videoId);
        if (existing.isEmpty()) {
            initPipelineTasks(videoId);
            return;
        }

        int resetCount = 0;
        for (VideoTask task : existing) {
            if (task.getStatus() == TaskStatus.FAILED || task.getStatus() == TaskStatus.CANCELED) {
                task.resetToPending();
                videoTaskRepository.updateById(task);
                resetCount++;
            }
        }
        log.info("视频 [{}] 重新提审，已成功复苏并重置 [{}] 个流水线子任务为 PENDING", videoId, resetCount);
    }

    /**
     * 标记指定子任务开始执行，流转为 RUNNING 状态。
     *
     * <p><b>防御性容错</b>：若任务记录尚未落库，先兜底创建后流转。</p>
     *
     * @param videoId 视频全局唯一 ID
     * @param taskType 目标子任务类型
     */
    @Transactional
    public void startTask(String videoId, TaskType taskType) {
        // 步骤 1：定位目标任务记录，不存在时执行防御性自动补建
        Optional<VideoTask> taskOpt = videoTaskRepository.findByVideoIdAndTaskType(videoId, taskType);
        VideoTask task = taskOpt.orElseGet(() -> {
            VideoTask newTask = VideoTask.create(videoId, taskType);
            videoTaskRepository.insert(newTask);
            return newTask;
        });

        // 步骤 2：流转为 RUNNING 态并持久化更新
        task.start();
        videoTaskRepository.updateById(task);
        log.info("视频 [{}] 任务 [{}] 进入执行中 (RUNNING)", videoId, taskType);
    }

    /**
     * 更新指定子任务的执行进度百分比。
     *
     * <p><b>业务场景</b>：外部转码 Worker 或多模态推理 Worker 定期上报计算进度时调用。</p>
     *
     * @param videoId 视频内部主键 ID
     * @param taskType 任务类型枚举
     * @param progress 进度百分比数值 (0-100)
     */
    @Transactional
    public void updateProgress(String videoId, TaskType taskType, int progress) {
        // 步骤 1：检索子任务并执行进度钳制更新
        videoTaskRepository.findByVideoIdAndTaskType(videoId, taskType).ifPresent(task -> {
            task.updateProgress(progress);
            // 步骤 2：回写数据库
            videoTaskRepository.updateById(task);
        });
    }

    /**
     * 标记指定子任务成功完成 (SUCCESS)，并主动触发分级就绪门禁评估。
     *
     * <p><b>核心联动</b>：若当前任务完成恰好促成基准门禁全部满足，门禁决策器将自动把视频发布为 PUBLISHED 并投递 Outbox 事件。</p>
     *
     * @param videoId 视频全局唯一 ID
     * @param taskType 任务类型枚举
     */
    @Transactional
    public void completeTask(String videoId, TaskType taskType) {
        // 步骤 1：查找或兜底创建目标任务
        Optional<VideoTask> taskOpt = videoTaskRepository.findByVideoIdAndTaskType(videoId, taskType);
        VideoTask task = taskOpt.orElseGet(() -> {
            VideoTask newTask = VideoTask.create(videoId, taskType);
            videoTaskRepository.insert(newTask);
            return newTask;
        });

        // 步骤 2：状态跃迁为 SUCCESS 并持久化
        task.complete();
        videoTaskRepository.updateById(task);
        log.info("视频 [{}] 任务 [{}] 成功完成 (SUCCESS)", videoId, taskType);

        // 步骤 3：主动触发分级就绪发布门禁决策
        publishGatekeeper.tryPublishIfEligible(videoId);
    }

    /**
     * 标记指定子任务执行失败 (FAILED)。
     *
     * <p><b>硬门禁熔断联动</b>：
     * 若失败的任务属于阻断性审核任务 (AUDIT)，将联动门禁决策器将聚合根视频直接驳回 (REJECTED) 并自动取消全流水线其余任务。
     * </p>
     *
     * @param videoId 视频内部主键 ID
     * @param taskType 任务类型枚举
     * @param errorMessage 失败具体原因说明
     */
    @Transactional
    public void failTask(String videoId, TaskType taskType, String errorMessage) {
        // 步骤 1：查找或兜底创建目标任务
        Optional<VideoTask> taskOpt = videoTaskRepository.findByVideoIdAndTaskType(videoId, taskType);
        VideoTask task = taskOpt.orElseGet(() -> {
            VideoTask newTask = VideoTask.create(videoId, taskType);
            videoTaskRepository.insert(newTask);
            return newTask;
        });

        // 步骤 2：状态跃迁为 FAILED 并沉淀错误信息
        task.fail(errorMessage);
        videoTaskRepository.updateById(task);
        log.warn("视频 [{}] 任务 [{}] 执行失败 (FAILED): {}", videoId, taskType, errorMessage);

        // 步骤 3：若属阻断性审核任务，立即级联驳回全流水线
        if (taskType == TaskType.AUDIT) {
            publishGatekeeper.rejectAndCancelPipeline(videoId, errorMessage);
        }
    }

    /**
     * 查询指定视频全量流水线任务聚合进度。
     *
     * <p>组装创作者工作台或管理端可视化流水线看板所需的全量指标。</p>
     *
     * @param videoId 视频内部主键 ID
     * @return 包含各任务执行状态、百分比及发布就绪资格的进度 DTO
     */
    public VideoResponses.PipelineProgress getPipelineProgress(String videoId) {
        // 步骤 1：查询视频主实体与所属全部子任务
        VideoContent video = videoContentRepository.findById(videoId).orElse(null);
        List<VideoTask> tasks = videoTaskRepository.findByVideoId(videoId);

        // 步骤 2：计算分级就绪门禁是否满足
        boolean eligible = video != null && publishGatekeeper.canPublish(video, tasks);
        String publishStatus = video != null ? video.getPublishStatus().name() : "UNKNOWN";

        // 步骤 3：映射转换各任务项详情列表
        List<VideoResponses.TaskProgressItem> items = tasks.stream()
                .map(t -> new VideoResponses.TaskProgressItem(
                        t.getId(),
                        t.getTaskType() != null ? t.getTaskType().getCode() : "UNKNOWN",
                        t.getTaskType() != null ? t.getTaskType().getDescription() : "未知任务",
                        t.getStatus() != null ? t.getStatus().getCode() : TaskStatus.PENDING.getCode(),
                        t.getProgress(),
                        t.getRetryCount(),
                        t.getErrorMessage(),
                        t.getStartedAt(),
                        t.getCompletedAt()
                ))
                .toList();

        // 步骤 4：组装响应对象
        return new VideoResponses.PipelineProgress(videoId, publishStatus, eligible, items);
    }
}
