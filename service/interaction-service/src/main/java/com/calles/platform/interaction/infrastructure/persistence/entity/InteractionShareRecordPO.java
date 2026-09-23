package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.share.InteractionShareRecord;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频分享幂等防重记录持久化对象 (PO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_share_record")
public class InteractionShareRecordPO {

    /** 主键 UUID。 */
    @TableId("id")
    private String id;

    /** 客户端幂等键。 */
    @TableField("idempotency_key")
    private String idempotencyKey;

    /** 用户账号 ID。 */
    @TableField("user_id")
    private String userId;

    /** 视频公开业务短码。 */
    @TableField("vid")
    private String vid;

    /** 逻辑删除标记：0=正常, 1=已删除。 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 转为领域模型。
     *
     * @return 领域实体
     */
    public InteractionShareRecord toDomain() {
        Instant instant = this.createdAt != null ? this.createdAt.toInstant(ZoneOffset.UTC) : Instant.now();
        return new InteractionShareRecord(this.id, this.idempotencyKey, this.userId, this.vid, instant,
                this.deleted != null && this.deleted == 1);
    }

    /**
     * 从领域模型转换为持久化对象。
     *
     * @param domain 领域实体
     * @return 持久化对象
     */
    public static InteractionShareRecordPO fromDomain(InteractionShareRecord domain) {
        if (domain == null) {
            return null;
        }
        LocalDateTime ldt = domain.getCreatedAt() != null
                ? LocalDateTime.ofInstant(domain.getCreatedAt(), ZoneOffset.UTC)
                : LocalDateTime.now(ZoneOffset.UTC);
        return InteractionShareRecordPO.builder()
                .id(domain.getId())
                .idempotencyKey(domain.getIdempotencyKey())
                .userId(domain.getUserId())
                .vid(domain.getVid())
                .deleted(domain.isDeleted() ? 1 : 0)
                .createdAt(ldt)
                .build();
    }
}
