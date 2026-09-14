package com.calles.platform.content.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.content.domain.model.tag.VideoTagRel;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频-标签关联持久化实体 (PO)。
 *
 * <p>映射底层数据库表 {@code video_tag_rel}，负责视频与标签之间多对多关系的物理存储。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("video_tag_rel")
public class VideoTagRelPO {

    /** 关联记录主键 ID (UUID 32位无短横线)。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 关联的视频内部主键 (对应 video_content.id)。 */
    @TableField("video_id")
    private String videoId;

    /** 关联的标签主键 (对应 content_tag.id)。 */
    @TableField("tag_id")
    private String tagId;

    /** 关联绑定创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 将持久化实体转换为领域关联实体。
     *
     * @return 对应的 {@link VideoTagRel} 领域实体
     */
    public VideoTagRel toDomain() {
        return VideoTagRel.builder()
                .id(this.id)
                .videoId(this.videoId)
                .tagId(this.tagId)
                .createdAt(this.createdAt)
                .build();
    }

    /**
     * 将领域关联实体转换为持久化实体。
     *
     * @param domain {@link VideoTagRel} 领域实体
     * @return 转换后的 {@link VideoTagRelPO}，若输入为 null 则返回 null
     */
    public static VideoTagRelPO fromDomain(VideoTagRel domain) {
        if (domain == null) {
            return null;
        }
        return VideoTagRelPO.builder()
                .id(domain.getId())
                .videoId(domain.getVideoId())
                .tagId(domain.getTagId())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
