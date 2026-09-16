package com.calles.platform.audit.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 审核判定明细持久化实体 (PO)。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("audit_detail")
public class AuditDetailPO {

    /** 主键雪花算法 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 关联的审核任务主键 ID。 */
    @TableField("task_id")
    private String taskId;

    /** 审查维度编码：TEXT, IMAGE, VIDEO。 */
    @TableField("dimension")
    private String dimension;

    /** 执行判定的引擎类型标识（例如 LOCAL_DFA, RULE_IMAGE 等）。 */
    @TableField("engine_type")
    private String engineType;

    /** 判定结果风险等级：NORMAL, SUSPICIOUS, ILLEGAL。 */
    @TableField("level")
    private String level;

    /** 判定置信度得分 (0.00 ~ 100.00)。 */
    @TableField("confidence")
    private BigDecimal confidence;

    /** 命中的敏感词或规则特征项（以逗号分隔，可为空）。 */
    @TableField("hit_words")
    private String hitWords;

    /** 详细判定日志说明或违规原因。 */
    @TableField("detail_log")
    private String detailLog;

    /** 记录创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 将持久化实体转换为领域模型实体。
     *
     * @return 领域实体
     */
    public AuditDetail toDomain() {
        return AuditDetail.builder()
                .id(this.id)
                .taskId(this.taskId)
                .dimension(AuditDimension.fromCode(this.dimension))
                .engineType(this.engineType)
                .level(ReviewLevel.fromCode(this.level))
                .confidence(this.confidence)
                .hitWords(this.hitWords)
                .detailLog(this.detailLog)
                .createdAt(this.createdAt != null ? this.createdAt.toInstant(ZoneOffset.UTC) : null)
                .build();
    }

    /**
     * 从领域实体构筑持久化对象。
     *
     * @param domain 领域实体
     * @return 持久化对象
     */
    public static AuditDetailPO fromDomain(AuditDetail domain) {
        if (domain == null) {
            return null;
        }
        return AuditDetailPO.builder()
                .id(domain.getId())
                .taskId(domain.getTaskId())
                .dimension(domain.getDimension().getCode())
                .engineType(domain.getEngineType())
                .level(domain.getLevel().getCode())
                .confidence(domain.getConfidence())
                .hitWords(domain.getHitWords())
                .detailLog(domain.getDetailLog())
                .createdAt(domain.getCreatedAt() != null ? LocalDateTime.ofInstant(domain.getCreatedAt(), ZoneOffset.UTC) : null)
                .build();
    }
}
