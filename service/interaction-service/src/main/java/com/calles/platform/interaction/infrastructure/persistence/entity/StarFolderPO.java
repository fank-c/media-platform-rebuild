package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.star.StarFolder;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收藏夹持久化对象 (PO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_star_folder")
public class StarFolderPO {

    /** 收藏夹主键 UUID。 */
    @TableId("id")
    private String id;

    /** 所属用户账号 ID。 */
    @TableField("user_id")
    private String userId;

    /** 收藏夹标题。 */
    @TableField("title")
    private String title;

    /** 是否系统默认收藏夹：1=是, 0=否。 */
    @TableField("is_default")
    private Integer isDefault;

    /** 状态：1=正常, 0=已删除。 */
    @TableField("status")
    private Integer status;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public StarFolder toDomain() {
        return StarFolder.builder()
                .id(this.id)
                .userId(this.userId)
                .title(this.title)
                .isDefault(this.isDefault != null && this.isDefault == 1)
                .status(this.status != null ? this.status : 1)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    public static StarFolderPO fromDomain(StarFolder domain) {
        if (domain == null) {
            return null;
        }
        return StarFolderPO.builder()
                .id(domain.getId())
                .userId(domain.getUserId())
                .title(domain.getTitle())
                .isDefault(domain.isDefault() ? 1 : 0)
                .status(domain.getStatus())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
