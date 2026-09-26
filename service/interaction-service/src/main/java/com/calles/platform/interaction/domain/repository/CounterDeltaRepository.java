package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.counter.CounterDelta;
import com.calles.platform.interaction.domain.model.counter.CounterType;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频公开互动统计计数增量领域仓储接口。
 *
 * <p>负责在业务事务内追加待汇总计数增量并关联业务事实来源，以及支撑后台批量调度任务顺序认领、标记处理与历史数据清理。</p>
 */
public interface CounterDeltaRepository {

    /**
     * 在当前业务事务内持久化追加一条计数增量记录。
     *
     * @param delta 增量领域实体
     */
    void append(CounterDelta delta);

    /**
     * 便捷方法：记录有效播放量增量并关联播放事实来源。
     *
     * @param vid 视频业务编码
     * @param sourceId 播放事实唯一标识（如历史记录ID与时间戳组合）
     * @param delta 播放增量（必须严格大于 0）
     */
    default void incrementViewCount(String vid, String sourceId, long delta) {
        append(CounterDelta.create(vid, CounterType.VIEW, delta, "WATCH_PLAY", sourceId));
    }

    /**
     * 便捷方法：记录点赞状态变动增量并关联点赞事实来源。
     *
     * @param vid 视频业务编码
     * @param sourceId 点赞记录标识与状态版本
     * @param delta 点赞变动量 (+1 或 -1)
     */
    default void adjustLikeCount(String vid, String sourceId, long delta) {
        append(CounterDelta.create(vid, CounterType.LIKE, delta, delta > 0 ? "LIKE_ACTIVE" : "LIKE_INACTIVE", sourceId));
    }

    /**
     * 便捷方法：记录收藏状态变动增量并关联收藏事实来源。
     *
     * @param vid 视频业务编码
     * @param sourceType 收藏事实类型（如 STAR_ACTIVE / STAR_INACTIVE）
     * @param sourceId 收藏明细标识或取消动作标识
     * @param delta 收藏变动量 (+1 或 -1)
     */
    default void adjustStarCount(String vid, String sourceType, String sourceId, long delta) {
        append(CounterDelta.create(vid, CounterType.STAR, delta, sourceType, sourceId));
    }

    /**
     * 便捷方法：记录分享增量并关联分享事实来源。
     *
     * @param vid 视频业务编码
     * @param sourceId 分享请求幂等键或记录标识
     * @param delta 分享增量（必须严格大于 0）
     */
    default void incrementShareCount(String vid, String sourceId, long delta) {
        append(CounterDelta.create(vid, CounterType.SHARE, delta, "SHARE", sourceId));
    }

    /**
     * 悲观顺序锁定当前未处理的待汇总增量列表。
     *
     * @param limit 单次最大锁定数量
     * @return 待汇总增量实体列表
     */
    List<CounterDelta> lockPendingForUpdate(int limit);

    /**
     * 批量标记增量记录为已汇总状态。
     *
     * @param ids 已汇总增量 ID 集合
     * @param processedAt 汇总完成时间
     */
    void markProcessed(List<Long> ids, LocalDateTime processedAt);

    /**
     * 清理超过保留期且已完成汇总的历史增量数据。
     *
     * @param threshold 保留期截止时间点
     * @param limit 单次最大删除行数
     * @return 实际删除行数
     */
    int deleteProcessedBefore(LocalDateTime threshold, int limit);
}
