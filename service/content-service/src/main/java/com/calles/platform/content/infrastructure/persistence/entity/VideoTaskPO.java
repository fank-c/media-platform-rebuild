package com.calles.platform.content.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.content.domain.model.task.TaskStatus;
import com.calles.platform.content.domain.model.task.TaskType;
import com.calles.platform.content.domain.model.task.VideoTask;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频异步流水线任务数据库持久化实体 (PO)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施层针对 {@code video_task} 表的 MyBatis-Plus 映射实体；</li>
 *   <li><b>主要职责</b>：直接承接数据库读写字段映射，并在领域层与持久化层之间充当防腐转换载体；</li>
 *   <li><b>不应承担的工作</b>：禁止在此类中编写业务流转、校验或状态决策逻辑。</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("video_task")
public class VideoTaskPO {

    /** 任务主键 ID (UUID 32位无短横线)，对应 video_task.id。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 所属视频的主键 ID (关联 video_content.id)，对应 video_task.video_id。 */
    @TableField("video_id")
    private String videoId;

    /** 任务类型字面量 (AUDIT, TRANSCODE_720P, TRANSCODE_1080P, TRANSCODE_4K, VECTOR_EMBEDDING)。 */
    @TableField("task_type")
    private String taskType;

    /** 任务状态编码字面量 (PENDING, RUNNING, SUCCESS, FAILED, CANCELED)。 */
    @TableField("status")
    private String status;

    /** 执行进度百分比 (0-100)，默认 0。 */
    @TableField("progress")
    private Integer progress;

    /** 已重试执行次数，默认 0。 */
    @TableField("retry_count")
    private Integer retryCount;

    /** 最大允许重试次数上限，默认 3。 */
    @TableField("max_retries")
    private Integer maxRetries;

    /** 失败时的错误说明或异常堆栈摘要，可为空。 */
    @TableField("error_message")
    private String errorMessage;

    /** 任务开始执行的时间戳，任务未启动时为 null。 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** 任务执行终态完成或被取消终止的时间戳，未结束时为 null。 */
    @TableField("completed_at")
    private LocalDateTime completedAt;

    /** 数据库记录创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 数据库记录最后更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 将数据库持久化实体 PO 转换为领域实体 {@link VideoTask}。
     *
     * <p>提供防御性空值回退（例如未设置的 progress/retryCount 自动回退为 0）。</p>
     *
     * @return 转换后的 {@link VideoTask} 领域实体
     */
    public VideoTask toDomain() {
        // 步骤 1：解析任务类型与状态枚举（支持防御性容错）
        TaskType parsedType = this.taskType != null ? TaskType.fromCode(this.taskType) : null;
        TaskStatus parsedStatus = this.status != null ? TaskStatus.fromCode(this.status) : TaskStatus.PENDING;

        // 步骤 2：组装并返回领域实体
        return VideoTask.builder()
                .id(this.id)
                .videoId(this.videoId)
                .taskType(parsedType)
                .status(parsedStatus)
                .progress(this.progress != null ? this.progress : 0)
                .retryCount(this.retryCount != null ? this.retryCount : 0)
                .maxRetries(this.maxRetries != null ? this.maxRetries : 3)
                .errorMessage(this.errorMessage)
                .startedAt(this.startedAt)
                .completedAt(this.completedAt)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    /**
     * 将领域实体 {@link VideoTask} 转换为数据库持久化实体 PO。
     *
     * @param domain 待转换的领域实体，为 null 时直接返回 null
     * @return 转换后的 {@link VideoTaskPO} 实例
     */
    public static VideoTaskPO fromDomain(VideoTask domain) {
        // 步骤 1：空值安全判断
        if (domain == null) {
            return null;
        }

        // 步骤 2：将领域实体各属性与枚举值提取转为持久化模型
        return VideoTaskPO.builder()
                .id(domain.getId())
                .videoId(domain.getVideoId())
                .taskType(domain.getTaskType() != null ? domain.getTaskType().getCode() : null)
                .status(domain.getStatus() != null ? domain.getStatus().getCode() : null)
                .progress(domain.getProgress())
                .retryCount(domain.getRetryCount())
                .maxRetries(domain.getMaxRetries())
                .errorMessage(domain.getErrorMessage())
                .startedAt(domain.getStartedAt())
                .completedAt(domain.getCompletedAt())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
