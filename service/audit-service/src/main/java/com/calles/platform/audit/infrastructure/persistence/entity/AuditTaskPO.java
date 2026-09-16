package com.calles.platform.audit.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.model.enums.CallbackStatus;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 审核任务持久化实体 (PO)。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("audit_task")
public class AuditTaskPO {

    /** 主键雪花算法 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 业务任务流水号（全局唯一，格式如 AUDIT_yyyyMMddHHmmssSSS_随机数）。 */
    @TableField("task_no")
    private String taskNo;

    /** 提审业务场景标识（如 VIDEO_POST）。 */
    @TableField("biz_type")
    private String bizType;

    /** 提审业务主体 ID（如视频发布记录 ID）。 */
    @TableField("biz_id")
    private String bizId;

    /** 提审视频聚合标识（如 vid）。 */
    @TableField("biz_vid")
    private String bizVid;

    /** 创作者作者用户 ID。 */
    @TableField("author_id")
    private String authorId;

    /** 提审标题文本快照。 */
    @TableField("title_snapshot")
    private String titleSnapshot;

    /** 提审正文/简介文本快照。 */
    @TableField("description_snapshot")
    private String descriptionSnapshot;

    /** 封面图片文件资产 ID。 */
    @TableField("cover_file_id")
    private String coverFileId;

    /** 主视频流文件资产 ID。 */
    @TableField("video_file_id")
    private String videoFileId;

    /** 审核生命周期阶段编码：RECEIVED, MACHINE_AUDITING, MANUAL_PENDING, FINISHED。 */
    @TableField("stage")
    private String stage;

    /** 审核判定裁决结论编码：PENDING, PASSED, REJECTED。 */
    @TableField("result")
    private String result;

    /** 违规驳回综合理由或命中原因说明。 */
    @TableField("reject_reason")
    private String rejectReason;

    /** 综合风险研判等级编码：NORMAL, SUSPICIOUS, ILLEGAL。 */
    @TableField("review_level")
    private String reviewLevel;

    /** 人工审核人操作员 ID（机审完结时为 SYSTEM）。 */
    @TableField("operator_id")
    private String operatorId;

    /** 审核结果回调下游内容服务的同步状态：PENDING, SUCCESS, FAILED。 */
    @TableField("callback_status")
    private String callbackStatus;

    /** 回调失败重试次数计数。 */
    @TableField("callback_retries")
    private Integer callbackRetries;

    /** 审核任务创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 审核任务最近状态更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 将持久化实体转换为领域模型实体。
     *
     * @return 审核任务聚合根实体
     */
    public AuditTask toDomain() {
        return AuditTask.builder()
                .id(this.id)
                .taskNo(this.taskNo)
                .bizType(this.bizType)
                .bizId(this.bizId)
                .bizVid(this.bizVid)
                .authorId(this.authorId)
                .titleSnapshot(this.titleSnapshot)
                .descriptionSnapshot(this.descriptionSnapshot)
                .coverFileId(this.coverFileId)
                .videoFileId(this.videoFileId)
                .stage(AuditStage.fromCode(this.stage))
                .result(AuditResult.fromCode(this.result))
                .rejectReason(this.rejectReason)
                .reviewLevel(ReviewLevel.fromCode(this.reviewLevel))
                .operatorId(this.operatorId)
                .callbackStatus(CallbackStatus.fromCode(this.callbackStatus))
                .callbackRetries(this.callbackRetries != null ? this.callbackRetries : 0)
                .createdAt(this.createdAt != null ? this.createdAt.toInstant(ZoneOffset.UTC) : null)
                .updatedAt(this.updatedAt != null ? this.updatedAt.toInstant(ZoneOffset.UTC) : null)
                .build();
    }

    /**
     * 从领域聚合根构建持久化对象。
     *
     * @param domain 审核任务聚合根实体
     * @return 审核任务持久化对象
     */
    public static AuditTaskPO fromDomain(AuditTask domain) {
        if (domain == null) {
            return null;
        }
        return AuditTaskPO.builder()
                .id(domain.getId())
                .taskNo(domain.getTaskNo())
                .bizType(domain.getBizType())
                .bizId(domain.getBizId())
                .bizVid(domain.getBizVid())
                .authorId(domain.getAuthorId())
                .titleSnapshot(domain.getTitleSnapshot())
                .descriptionSnapshot(domain.getDescriptionSnapshot())
                .coverFileId(domain.getCoverFileId())
                .videoFileId(domain.getVideoFileId())
                .stage(domain.getStage().getCode())
                .result(domain.getResult().getCode())
                .rejectReason(domain.getRejectReason())
                .reviewLevel(domain.getReviewLevel().getCode())
                .operatorId(domain.getOperatorId())
                .callbackStatus(domain.getCallbackStatus().getCode())
                .callbackRetries(domain.getCallbackRetries())
                .createdAt(domain.getCreatedAt() != null ? LocalDateTime.ofInstant(domain.getCreatedAt(), ZoneOffset.UTC) : null)
                .updatedAt(domain.getUpdatedAt() != null ? LocalDateTime.ofInstant(domain.getUpdatedAt(), ZoneOffset.UTC) : null)
                .build();
    }
}
