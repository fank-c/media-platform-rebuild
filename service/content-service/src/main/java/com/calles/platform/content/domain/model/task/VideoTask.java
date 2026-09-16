package com.calles.platform.content.domain.model.task;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频发布流水线子任务领域实体 (VideoTask)。
 *
 * <p>职责与设计原则：
 * <ul>
 *   <li><b>所属边界</b>：视频提审后的异步编排与门禁子域；</li>
 *   <li><b>聚合关系</b>：弱关联依附于 {@link com.calles.platform.content.domain.model.video.VideoContent}；</li>
 *   <li><b>核心行为</b>：封装任务从排队、开始执行、进度汇报、完成、失败重试及取消的状态机跃迁逻辑。</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoTask {

    /** 任务主键 ID (UUID 32位无短横线)。 */
    private String id;

    /** 所属视频内部全局主键 ID。 */
    private String videoId;

    /** 任务类型 (AUDIT, TRANSCODE_720P, TRANSCODE_1080P, TRANSCODE_4K, VECTOR_EMBEDDING)。 */
    private TaskType taskType;

    /** 任务状态 (PENDING, RUNNING, SUCCESS, FAILED, CANCELED)。 */
    private TaskStatus status;

    /** 进度百分比 (0-100)。 */
    private int progress;

    /** 已重试次数。 */
    private int retryCount;

    /** 最大允许重试次数。 */
    private int maxRetries;

    /** 失败原因说明。 */
    private String errorMessage;

    /** 任务开始时间。 */
    private LocalDateTime startedAt;

    /** 任务完成/终止时间。 */
    private LocalDateTime completedAt;

    /** 记录创建时间。 */
    private LocalDateTime createdAt;

    /** 记录更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：创建一个初始处于排队状态 (PENDING) 的全新子任务实例。
     *
     * <p>默认重试上限为 3 次，进度为 0，创建时间与更新时间初始化为当前系统时间。</p>
     *
     * @param videoId 关联的视频内部全局唯一主键 ID，不可为 null
     * @param taskType 任务类型枚举，不可为 null
     * @return 初始化的视频子任务领域实体
     * @throws NullPointerException 若传入参数为 null
     */
    public static VideoTask create(String videoId, TaskType taskType) {
        // 步骤 1：记录初始化基准时间戳
        LocalDateTime now = LocalDateTime.now();

        // 步骤 2：构建初始状态为 PENDING 的任务领域实体
        return VideoTask.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .videoId(videoId)
                .taskType(taskType)
                .status(TaskStatus.PENDING)
                .progress(0)
                .retryCount(0)
                .maxRetries(3)
                .errorMessage(null)
                .startedAt(null)
                .completedAt(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 启动任务执行，将任务状态流转为 RUNNING。
     *
     * <p><b>幂等性说明</b>：若任务已处于终态 (SUCCESS 或 CANCELED)，则直接忽略跳过，避免非法状态逆转；<br>
     * <b>副作用</b>：更新 startedAt 与 updatedAt 时间戳。</p>
     */
    public void start() {
        // 步骤 1：终态防护检查，已成功或已取消的任务不得重新进入执行态
        if (this.status == TaskStatus.SUCCESS || this.status == TaskStatus.CANCELED) {
            return;
        }

        // 步骤 2：流转为运行中状态并更新开始时间戳
        this.status = TaskStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 汇报任务执行进度。
     *
     * <p><b>取值约束</b>：进度百分比将被自动钳制在 [0, 100] 区间内；<br>
     * <b>幂等性说明</b>：若任务已达到终态 (terminal=true)，则拒绝继续更新进度；<br>
     * <b>副作用</b>：强制置状态为 RUNNING 并刷新 updatedAt 时间戳。</p>
     *
     * @param progress 任务进度百分比数值 (0 - 100)
     */
    public void updateProgress(int progress) {
        // 步骤 1：若已进入终态（成功/失败/取消），忽略后续进度汇报
        if (this.status.isTerminal()) {
            return;
        }

        // 步骤 2：状态置为运行中，并将进度钳制在合法 0-100 区间
        this.status = TaskStatus.RUNNING;
        this.progress = Math.max(0, Math.min(100, progress));
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记任务执行成功完成。
     *
     * <p><b>状态跃迁</b>：状态流转为 SUCCESS，进度自动设为 100，并清空历史错误信息；<br>
     * <b>副作用</b>：记录 completedAt 与 updatedAt 时间戳。</p>
     */
    public void complete() {
        // 步骤 1：更新任务终态为 SUCCESS 并将进度置满
        this.status = TaskStatus.SUCCESS;
        this.progress = 100;
        this.errorMessage = null;

        // 步骤 2：记录完成时间戳
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 标记任务执行失败。
     *
     * <p><b>状态跃迁</b>：状态流转为 FAILED，记录失败原因摘要；<br>
     * <b>副作用</b>：记录 completedAt 与 updatedAt 时间戳。</p>
     *
     * @param errorMessage 失败具体原因说明或异常摘要
     */
    public void fail(String errorMessage) {
        // 步骤 1：更新任务终态为 FAILED 并沉淀错误信息
        this.status = TaskStatus.FAILED;
        this.errorMessage = errorMessage;

        // 步骤 2：记录失败终止时间戳
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 取消终止任务。
     *
     * <p><b>幂等性说明</b>：若任务已处于 SUCCESS 状态，则不可取消；<br>
     * <b>副作用</b>：状态流转为 CANCELED，记录取消原因与 completedAt 时间戳。</p>
     *
     * @param reason 取消操作的具体原因说明
     */
    public void cancel(String reason) {
        // 步骤 1：已成功完成的任务不允许逆转为取消态
        if (this.status == TaskStatus.SUCCESS) {
            return;
        }

        // 步骤 2：流转为 CANCELED 终态并记录原因
        this.status = TaskStatus.CANCELED;
        this.errorMessage = reason;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 判断当前任务是否满足自愈重试条件。
     *
     * <p><b>判断规则</b>：
     * 仅当任务处于可重试状态 (FAILED 失败态或 RUNNING 超时未响应) 且重试次数严格小于最大允许次数 maxRetries 时返回 true。
     * </p>
     *
     * @return true 若允许重新调度重试，false 否则
     */
    public boolean canRetry() {
        return (this.status == TaskStatus.FAILED || this.status == TaskStatus.RUNNING)
                && this.retryCount < this.maxRetries;
    }

    /**
     * 重置任务为排队重试状态 (PENDING)。
     *
     * <p><b>业务约束</b>：必须先通过 {@link #canRetry()} 校验；<br>
     * <b>副作用</b>：自增 retryCount，清空错误说明与时间戳，重置进度为 0。</p>
     *
     * @throws IllegalStateException 当超出重试上限或当前状态不允许重试时抛出
     */
    public void markForRetry() {
        // 步骤 1：重试资格硬前置校验
        if (!canRetry()) {
            throw new IllegalStateException("任务不可重试，超出重试限制或状态不可重试");
        }

        // 步骤 2：重试计数递增
        this.retryCount++;

        // 步骤 3：重置执行状态为 PENDING 排队就绪，清理进度与执行时间戳
        this.status = TaskStatus.PENDING;
        this.progress = 0;
        this.errorMessage = null;
        this.startedAt = null;
        this.completedAt = null;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 重新提审时复苏并重置任务为排队就绪状态 (PENDING)。
     *
     * <p><b>业务说明</b>：用于创作者被驳回后重新提审，将 {@link TaskStatus#FAILED} 或 {@link TaskStatus#CANCELED} 状态的流水线任务复苏；<br>
     * <b>副作用</b>：重置状态为 PENDING，清空错误说明与完成时间戳，重置进度为 0。</p>
     */
    public void resetToPending() {
        this.status = TaskStatus.PENDING;
        this.progress = 0;
        this.errorMessage = null;
        this.startedAt = null;
        this.completedAt = null;
        this.updatedAt = LocalDateTime.now();
    }
}
