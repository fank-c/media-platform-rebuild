package com.calles.platform.audit.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.audit.domain.model.AuditSensitiveWord;
import com.calles.platform.audit.domain.model.enums.CommonStatus;
import com.calles.platform.audit.domain.model.enums.WordCategory;
import com.calles.platform.audit.domain.model.enums.WordLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 敏感词持久化实体 (PO)。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("audit_sensitive_word")
public class AuditSensitiveWordPO {

    /** 主键雪花算法 ID。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 敏感词词条文本（全局唯一，前缀树匹配基准）。 */
    @TableField("word")
    private String word;

    /** 敏感词所属分类：POLITICS, PORN, VIOLENCE, ABUSE, AD, GENERAL。 */
    @TableField("category")
    private String category;

    /** 敏感词拦截级别：ILLEGAL（直接阻断驳回）, SUSPICIOUS（疑似转人审）。 */
    @TableField("level")
    private String level;

    /** 词条生效状态：ACTIVE（正常生效中）, DISABLED（停用）。 */
    @TableField("status")
    private String status;

    /** 词条入库创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 词条最近更新修改时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 将持久化实体转换为领域模型实体。
     *
     * @return 敏感词领域实体
     */
    public AuditSensitiveWord toDomain() {
        return AuditSensitiveWord.builder()
                .id(this.id)
                .word(this.word)
                .category(WordCategory.fromCode(this.category))
                .level(WordLevel.fromCode(this.level))
                .status(CommonStatus.fromCode(this.status))
                .createdAt(this.createdAt != null ? this.createdAt.toInstant(ZoneOffset.UTC) : null)
                .updatedAt(this.updatedAt != null ? this.updatedAt.toInstant(ZoneOffset.UTC) : null)
                .build();
    }

    /**
     * 从领域实体构筑持久化对象。
     *
     * @param domain 敏感词领域实体
     * @return 敏感词持久化对象
     */
    public static AuditSensitiveWordPO fromDomain(AuditSensitiveWord domain) {
        if (domain == null) {
            return null;
        }
        return AuditSensitiveWordPO.builder()
                .id(domain.getId())
                .word(domain.getWord())
                .category(domain.getCategory().getCode())
                .level(domain.getLevel().getCode())
                .status(domain.getStatus().getCode())
                .createdAt(domain.getCreatedAt() != null ? LocalDateTime.ofInstant(domain.getCreatedAt(), ZoneOffset.UTC) : null)
                .updatedAt(domain.getUpdatedAt() != null ? LocalDateTime.ofInstant(domain.getUpdatedAt(), ZoneOffset.UTC) : null)
                .build();
    }
}
