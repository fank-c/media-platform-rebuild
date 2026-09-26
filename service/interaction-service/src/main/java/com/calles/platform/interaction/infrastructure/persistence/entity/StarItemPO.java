package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.star.StarItem;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 收藏明细持久化对象 (PO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_star_item")
public class StarItemPO {

    /** 明细主键 UUID。 */
    @TableId("id")
    private String id;

    /** 所属收藏夹 ID。 */
    @TableField("folder_id")
    private String folderId;

    /** 视频公开业务短码。 */
    @TableField("vid")
    private String vid;

    /** 用户账号 ID。 */
    @TableField("user_id")
    private String userId;

    /** 明细版本号。 */
    @TableField("version")
    private Long version;

    /** 逻辑删除标记：0=正常, 1=已删除。 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /** 收藏创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    public StarItem toDomain() {
        return StarItem.builder()
                .id(this.id)
                .folderId(this.folderId)
                .vid(this.vid)
                .userId(this.userId)
                .version(this.version != null ? this.version : 1L)
                .createdAt(this.createdAt)
                .deleted(this.deleted != null && this.deleted == 1)
                .build();
    }

    public static StarItemPO fromDomain(StarItem domain) {
        if (domain == null) {
            return null;
        }
        return StarItemPO.builder()
                .id(domain.getId())
                .folderId(domain.getFolderId())
                .vid(domain.getVid())
                .userId(domain.getUserId())
                .version(domain.getVersion())
                .createdAt(domain.getCreatedAt())
                .deleted(domain.isDeleted() ? 1 : 0)
                .build();
    }
}
