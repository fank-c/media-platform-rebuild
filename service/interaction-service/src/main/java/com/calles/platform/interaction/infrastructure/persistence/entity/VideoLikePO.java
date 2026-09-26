package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.like.LikeStatus;
import com.calles.platform.interaction.domain.model.like.VideoLike;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频点赞持久化对象 (PO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_like")
public class VideoLikePO {

    /** 主键 UUID。 */
    @TableId("id")
    private String id;

    /** 视频业务公开短码。 */
    @TableField("vid")
    private String vid;

    /** 用户账号 ID。 */
    @TableField("user_id")
    private String userId;

    /** 点赞状态：1=已赞, 0=已取消。 */
    @TableField("status")
    private Integer status;

    /** 状态变更版本号。 */
    @TableField("version")
    private Long version;

    /** 逻辑删除标记：0=正常, 1=已删除。 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public VideoLike toDomain() {
        return VideoLike.builder()
                .id(this.id)
                .vid(this.vid)
                .userId(this.userId)
                .status(this.status != null ? LikeStatus.fromValue(this.status) : LikeStatus.CANCELLED)
                .version(this.version != null ? this.version : 1L)
                .deleted(this.deleted != null && this.deleted == 1)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    public static VideoLikePO fromDomain(VideoLike domain) {
        if (domain == null) {
            return null;
        }
        return VideoLikePO.builder()
                .id(domain.getId())
                .vid(domain.getVid())
                .userId(domain.getUserId())
                .status(domain.getStatus() != null ? domain.getStatus().getValue() : LikeStatus.CANCELLED.getValue())
                .version(domain.getVersion())
                .deleted(domain.isDeleted() ? 1 : 0)
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
