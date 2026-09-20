package com.calles.platform.content.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.content.domain.model.CommonStatus;
import com.calles.platform.content.domain.model.tag.ContentTag;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 标签全局字典持久化实体 (PO)。
 *
 * <p>映射底层数据库表 {@code content_tag}，负责轻量标签字典及全站热度统计的持久化存储。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("content_tag")
public class ContentTagPO {

    /** 标签主键 ID (UUID 32位无短横线)。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 标签唯一名称 (例如 "Java", "微服务")。 */
    @TableField("name")
    private String name;

    /** 引用热度计数（关联的已发布视频数量）。 */
    @TableField("reference_count")
    private Long referenceCount;

    /** 标签可用状态 (ACTIVE / DISABLED)。 */
    @TableField("status")
    private String status;

    /** 标签类型 (DOMAIN / TOPIC)。 */
    @TableField("tag_type")
    private String tagType;

    /** 记录创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 记录最后更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 将数据库持久化实体转换为领域聚合实体。
     *
     * @return 对应的 {@link ContentTag} 领域实体
     */
    public ContentTag toDomain() {
        return ContentTag.builder()
                .id(this.id)
                .name(this.name)
                .tagType(this.tagType != null ? com.calles.platform.content.domain.model.tag.TagType.fromCode(this.tagType) : com.calles.platform.content.domain.model.tag.TagType.TOPIC)
                .referenceCount(this.referenceCount != null ? this.referenceCount : 0L)
                .status(this.status != null ? CommonStatus.valueOf(this.status) : CommonStatus.ACTIVE)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    /**
     * 将领域聚合实体转换为数据库持久化实体。
     *
     * @param domain {@link ContentTag} 领域实体
     * @return 转换后的 {@link ContentTagPO}，若输入为 null 则返回 null
     */
    public static ContentTagPO fromDomain(ContentTag domain) {
        if (domain == null) {
            return null;
        }
        return ContentTagPO.builder()
                .id(domain.getId())
                .name(domain.getName())
                .tagType(domain.getTagType() != null ? domain.getTagType().getCode() : com.calles.platform.content.domain.model.tag.TagType.TOPIC.getCode())
                .referenceCount(domain.getReferenceCount())
                .status(domain.getStatus() != null ? domain.getStatus().getValue() : CommonStatus.ACTIVE.getValue())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
