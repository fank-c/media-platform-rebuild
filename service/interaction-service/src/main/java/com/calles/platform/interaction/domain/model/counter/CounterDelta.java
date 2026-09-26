package com.calles.platform.interaction.domain.model.counter;

import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 视频公开互动统计待汇总增量领域实体。
 *
 * <p>用于在业务事实（点赞、收藏、有效播放、分享）发生的数据库本地事务内记录计数增量与业务事实来源引用，
 * 支撑后台异步批量聚合、对账审计与幂等重放。</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CounterDelta {

    /** 增量记录物理主键（持久化后生成）。 */
    private Long id;

    /** 视频公开业务编码。 */
    private String vid;

    /** 计数变动类型维度。 */
    private CounterType type;

    /** 计数值变动量（通常为 +1 或 -1，严禁为 0；VIEW/SHARE 必须大于 0）。 */
    private long delta;

    /** 业务事实来源类型（如 LIKE_ACTIVE, LIKE_INACTIVE, STAR_ACTIVE, STAR_INACTIVE, WATCH_PLAY, SHARE）。 */
    private String sourceType;

    /** 业务事实来源主键或幂等标识。 */
    private String sourceId;

    /** 增量生成时间戳。 */
    private LocalDateTime createdAt;

    /** 增量汇总完成时间戳（未处理前为 null）。 */
    private LocalDateTime processedAt;

    /**
     * 工厂方法：在业务事务内创建新的待汇总增量客观事实并绑定来源追踪信息。
     *
     * @param vid 视频公开短码 (不可为空)
     * @param type 计数维度类型 (不可为空)
     * @param delta 变动量 (不可为 0；VIEW/SHARE 必须为严格正整数)
     * @param sourceType 业务事实类型 (不可为空)
     * @param sourceId 业务事实唯一标识 (不可为空)
     * @return 待持久化的计数增量实体
     */
    public static CounterDelta create(String vid, CounterType type, long delta, String sourceType, String sourceId) {
        if (vid == null || vid.isBlank()) {
            throw new IllegalArgumentException("视频业务短码不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("计数类型不能为空");
        }
        if (delta == 0) {
            throw new IllegalArgumentException("计数变动量不能为 0");
        }
        // P1 约束防御：播放量与分享量只增不减，严禁写入负增量
        if ((type == CounterType.VIEW || type == CounterType.SHARE) && delta <= 0) {
            throw new IllegalArgumentException("播放量和分享量增量必须为严格正整数: delta=" + delta);
        }
        // P2 事实来源追溯约束防御
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("业务事实来源类型不能为空");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("业务事实来源标识不能为空");
        }

        return CounterDelta.builder()
                .vid(vid.trim())
                .type(type)
                .delta(delta)
                .sourceType(sourceType.trim())
                .sourceId(sourceId.trim())
                .createdAt(LocalDateTime.now())
                .processedAt(null)
                .build();
    }

    /**
     * 从持久化存储还原增量实体。
     *
     * @param id 增量物理主键
     * @param vid 视频公开短码
     * @param type 计数类型
     * @param delta 变动量
     * @param sourceType 业务事实来源类型
     * @param sourceId 业务事实来源标识
     * @param createdAt 创建时间
     * @param processedAt 汇总完成时间
     * @return 领域实体实例
     */
    public static CounterDelta reconstitute(Long id, String vid, CounterType type, long delta,
                                            String sourceType, String sourceId,
                                            LocalDateTime createdAt, LocalDateTime processedAt) {
        return CounterDelta.builder()
                .id(id)
                .vid(vid)
                .type(type)
                .delta(delta)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .createdAt(createdAt)
                .processedAt(processedAt)
                .build();
    }

    /**
     * 从持久化存储还原增量实体（极简/向前兼容重载）。
     *
     * @param id 增量物理主键
     * @param vid 视频公开短码
     * @param type 计数类型
     * @param delta 变动量
     * @param createdAt 创建时间
     * @param processedAt 汇总完成时间
     * @return 领域实体实例
     */
    public static CounterDelta reconstitute(Long id, String vid, CounterType type, long delta,
                                            LocalDateTime createdAt, LocalDateTime processedAt) {
        return reconstitute(id, vid, type, delta, "UNKNOWN", "UNKNOWN", createdAt, processedAt);
    }

    /**
     * 判定该增量是否已被后台汇总任务处理完成。
     *
     * @return true 表示已汇总，false 表示待处理
     */
    public boolean isProcessed() {
        return this.processedAt != null;
    }
}
