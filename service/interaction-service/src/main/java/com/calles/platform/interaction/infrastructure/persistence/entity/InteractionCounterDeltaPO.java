package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.counter.CounterDelta;
import com.calles.platform.interaction.domain.model.counter.CounterType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待汇总视频计数增量持久化对象 (PO)。
 *
 * <p>对应数据表 {@code interaction_counter_delta}，通过 MyBatis-Plus 实现持久化对象与关系表的结构映射。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_counter_delta")
public class InteractionCounterDeltaPO {

    /** 增量自增主键与汇总顺序 ID。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 视频公开业务短码。 */
    @TableField("vid")
    private String vid;

    /** 计数类型字符串 (VIEW, LIKE, STAR, SHARE)。 */
    @TableField("counter_type")
    private String counterType;

    /** 计数增量数值。 */
    @TableField("delta")
    private Long delta;

    /** 业务事实来源类型（如 LIKE_ACTIVE, LIKE_INACTIVE, STAR_ACTIVE, STAR_INACTIVE, WATCH_PLAY, SHARE）。 */
    @TableField("source_type")
    private String sourceType;

    /** 业务事实来源主键或幂等标识。 */
    @TableField("source_id")
    private String sourceId;

    /** 记录创建时间戳。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 汇总成功处理时间戳（待处理为 null）。 */
    @TableField("processed_at")
    private LocalDateTime processedAt;

    /**
     * 将持久化对象转换为领域实体。
     *
     * @return 计数增量领域实体
     */
    public CounterDelta toDomain() {
        return CounterDelta.reconstitute(
                this.id,
                this.vid,
                CounterType.fromCode(this.counterType),
                this.delta != null ? this.delta : 0L,
                this.sourceType,
                this.sourceId,
                this.createdAt,
                this.processedAt
        );
    }

    /**
     * 从领域实体构建持久化对象。
     *
     * @param domain 增量领域实体
     * @return 持久化对象；入参为空时返回 null
     */
    public static InteractionCounterDeltaPO fromDomain(CounterDelta domain) {
        if (domain == null) {
            return null;
        }
        return InteractionCounterDeltaPO.builder()
                .id(domain.getId())
                .vid(domain.getVid())
                .counterType(domain.getType() != null ? domain.getType().getCode() : null)
                .delta(domain.getDelta())
                .sourceType(domain.getSourceType())
                .sourceId(domain.getSourceId())
                .createdAt(domain.getCreatedAt())
                .processedAt(domain.getProcessedAt())
                .build();
    }
}
