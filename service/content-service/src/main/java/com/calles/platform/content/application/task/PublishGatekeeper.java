package com.calles.platform.content.application.task;

import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.domain.repository.VideoTaskRepository;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxMapper;
import com.calles.platform.content.infrastructure.outbox.ContentOutboxRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频分级就绪发布门禁决策器 (PublishGatekeeper)。
 *
 * <p>职责与核心门禁规则：
 * <ul>
 *   <li><b>所属边界</b>：发布流水线准入与自动发布决策核心组件；</li>
 *   <li><b>分级就绪策略</b>：
 *     <ol>
 *       <li>内容合规审核 (AUDIT) 必须为 {@link TaskStatus#SUCCESS}；</li>
 *       <li>多模态向量检索计算 (VECTOR_EMBEDDING) 必须为 {@link TaskStatus#SUCCESS}；</li>
 *       <li>基准播放流 (720P 或 1080P) 至少有一条转码完成为 {@link TaskStatus#SUCCESS}；</li>
 *       <li>超高清 4K 转码 (TRANSCODE_4K) 为非阻断性可选流，不阻塞上线，支持异步就绪。</li>
 *     </ol>
 *   </li>
 *   <li><b>发布触发</b>：一旦同时满足上述 3 项基准门禁，自动将聚合根流转为 {@link PublishStatus#PUBLISHED} 并写入 Outbox；</li>
 *   <li><b>审核驳回决策</b>：若审核任务失败，触发视频驳回 (REJECTED) 并自动取消其余进行中或排队中的子任务。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishGatekeeper {

    /** 视频聚合根仓储。 */
    private final VideoContentRepository videoContentRepository;

    /** 视频子任务仓储。 */
    private final VideoTaskRepository videoTaskRepository;

    /** 事务性 Outbox 事件持久化 Mapper。 */
    private final ContentOutboxMapper contentOutboxMapper;

    /**
     * 判断当前任务集合是否满足分级就绪公开发布门禁。
     *
     * <p><b>分级判定规则</b>：
     * 1. 视频必须处于审核中 (AUDITING)；<br>
     * 2. 若任务列表为空，降级放行（兼容旧单步提审直接审核流）；<br>
     * 3. 内容安全风控审核 (AUDIT) 必须完成为 SUCCESS；<br>
     * 4. 多模态语义特征向量计算 (VECTOR_EMBEDDING) 必须完成为 SUCCESS；<br>
     * 5. 720P 或 1080P 基准清晰度流至少有一个完成为 SUCCESS；<br>
     * 6. 4K 等超清转码非阻断，不阻塞当前门禁判决。
     * </p>
     *
     * @param video 视频聚合根实体，可为 null
     * @param tasks 视频名下的全部子任务列表
     * @return true 若满足发布门禁条件，false 否则
     */
    public boolean canPublish(VideoContent video, List<VideoTask> tasks) {
        // 步骤 1：前置状态校验：仅 AUDITING 审核中的视频允许评估门禁发布
        if (video == null || video.getPublishStatus() != PublishStatus.AUDITING) {
            return false;
        }

        // 步骤 2：兼容降级逻辑：若历史数据未生成独立流水线任务，兼容直接发布
        if (tasks == null || tasks.isEmpty()) {
            return true;
        }

        // 步骤 3：构建任务类型 -> 执行状态的高效映射字典
        Map<TaskType, TaskStatus> taskStatusMap = tasks.stream()
                .filter(t -> t.getTaskType() != null && t.getStatus() != null)
                .collect(Collectors.toMap(VideoTask::getTaskType, VideoTask::getStatus, (a, b) -> a));

        // 步骤 4：门禁判定 1 —— 内容安全风控机审/人审必须成功通过 (AUDIT == SUCCESS)
        boolean auditPassed = taskStatusMap.get(TaskType.AUDIT) == TaskStatus.SUCCESS;

        // 步骤 5：门禁判定 2 —— 多模态特征向量提取计算必须完成 (VECTOR_EMBEDDING == SUCCESS)
        boolean vectorPassed = taskStatusMap.get(TaskType.VECTOR_EMBEDDING) == TaskStatus.SUCCESS;

        // 步骤 6：门禁判定 3 —— 至少存在一条可用基准播放清晰度流 (720P 或 1080P 任一完成即可)
        boolean stream720pReady = taskStatusMap.get(TaskType.TRANSCODE_720P) == TaskStatus.SUCCESS;
        boolean stream1080pReady = taskStatusMap.get(TaskType.TRANSCODE_1080P) == TaskStatus.SUCCESS;
        boolean baselineStreamReady = stream720pReady || stream1080pReady;

        // 步骤 7：三项硬门禁同时满足方可准入发布
        return auditPassed && vectorPassed && baselineStreamReady;
    }

    /**
     * 评估并在满足门禁条件时自动将视频发布上线（本地原子事务保证）。
     *
     * <p><b>事务与一致性说明</b>：
     * 本方法运行于 Spring 本地声明式事务 {@code @Transactional} 边界内，保证视频聚合根流转为 PUBLISHED
     * 与持久化 {@code content.video.published} Outbox 事件的原子性，下游推荐微服务通过轮询投递消费该事件。
     * </p>
     *
     * @param videoId 视频全局唯一主键 ID
     * @return true 若成功触发并完成自动发布，false 若尚未满足门禁或视频已被并发修改/非 AUDITING 状态
     */
    @Transactional
    public boolean tryPublishIfEligible(String videoId) {
        // 步骤 1：查询视频聚合根并校验当前生命周期状态
        VideoContent video = videoContentRepository.findById(videoId).orElse(null);
        if (video == null || video.getPublishStatus() != PublishStatus.AUDITING) {
            return false;
        }

        // 步骤 2：检索名下所有流水线子任务并执行门禁评估
        List<VideoTask> tasks = videoTaskRepository.findByVideoId(videoId);
        if (!canPublish(video, tasks)) {
            log.debug("视频 [{}] 尚未满足分级就绪门禁，继续等待异步任务推进", videoId);
            return false;
        }

        // 步骤 3：达成门禁条件，聚合根状态跃迁为 PUBLISHED
        LocalDateTime now = LocalDateTime.now();
        video.publish(now);

        // 步骤 4：持久化更新视频实体，利用版本号防范并发修改冲突
        int updated = videoContentRepository.updateById(video);
        if (updated == 0) {
            log.warn("视频 [{}] 发布更新发生并发冲突，退出本次尝试", videoId);
            return false;
        }

        // 步骤 5：在同一本地事务中写入 Outbox 领域发布事件，通知下游搜索、推荐系统即时上线
        Instant instantNow = Instant.now();
        ContentOutboxRecord outbox = ContentOutboxRecord.of(
                video.getId(),
                "content.video.published",
                String.format("{\"videoId\":\"%s\",\"vid\":\"%s\",\"authorId\":\"%s\",\"videoFileId\":\"%s\",\"coverFileId\":\"%s\",\"publishedAt\":\"%s\"}",
                        video.getId(), video.getVid(), video.getAuthorId(), video.getVideoFileId(), video.getCoverFileId(), video.getPublishedAt()),
                instantNow
        );
        contentOutboxMapper.insert(outbox, Timestamp.from(instantNow), "PENDING", Timestamp.from(instantNow));

        // 步骤 6：记录审计日志
        log.info("视频 [{}] 已通过分级就绪门禁，正式自动发布上线！", videoId);
        return true;
    }

    /**
     * 审核失败打回处理：流转聚合根为 REJECTED，级联取消其他未终止的流水线子任务，并发布驳回事件。
     *
     * <p><b>业务场景</b>：当机审或人工审核任务判定视频违规、侵权或不合规时调用。<br>
     * <b>资源防浪费</b>：自动取消仍处于排队或转码中的其余子任务，避免白白耗费转码集群算力与存储带宽。</p>
     *
     * @param videoId 视频全局唯一主键 ID
     * @param rejectReason 驳回具体原因或违规说明
     */
    @Transactional
    public void rejectAndCancelPipeline(String videoId, String rejectReason) {
        // 步骤 1：查询视频聚合根并校验审核中状态
        VideoContent video = videoContentRepository.findById(videoId).orElse(null);
        if (video == null || video.getPublishStatus() != PublishStatus.AUDITING) {
            log.info("视频 [{}] 当前非 AUDITING 状态，忽略驳回打回处理", videoId);
            return;
        }

        // 步骤 2：聚合根执行领域行为：状态流转为 REJECTED 并沉淀驳回原因
        video.reject(rejectReason);
        videoContentRepository.updateById(video);

        // 步骤 3：级联取消所有非终态的子任务，释放转码算力与队列资源
        List<VideoTask> tasks = videoTaskRepository.findByVideoId(videoId);
        for (VideoTask task : tasks) {
            if (!task.getStatus().isTerminal()) {
                task.cancel("审核未通过，自动终止流水线任务");
                videoTaskRepository.updateById(task);
            }
        }

        // 步骤 4：在本地事务中记录 Outbox 驳回事件，供通知中心等下游感知
        Instant instantNow = Instant.now();
        ContentOutboxRecord outbox = ContentOutboxRecord.of(
                video.getId(),
                "content.video.rejected",
                String.format("{\"videoId\":\"%s\",\"vid\":\"%s\",\"reason\":\"%s\"}",
                        video.getId(), video.getVid(), video.getRejectReason()),
                instantNow
        );
        contentOutboxMapper.insert(outbox, Timestamp.from(instantNow), "PENDING", Timestamp.from(instantNow));

        // 步骤 5：记录操作日志
        log.info("视频 [{}] 审核驳回，已自动终止流水线其余子任务", videoId);
    }
}
