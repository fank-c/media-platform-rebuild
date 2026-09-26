package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.counter.CounterType;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 已汇总视频公开互动统计计数快照仓储接口。
 *
 * <p>提供公开计数快照的查询能力，以及支撑后台批量汇总用例原子累加各维度计数增量。</p>
 */
public interface VideoCounterRepository {

    /**
     * 根据视频公开短码查询已汇总互动统计。
     *
     * @param vid 视频公开编码
     * @return 统计快照 Optional
     */
    Optional<VideoCounter> findByVid(String vid);

    /**
     * 批量查询已汇总互动统计。
     *
     * @param vids 视频公开编码集合
     * @return 统计快照列表
     */
    List<VideoCounter> findByVids(Collection<String> vids);

    /**
     * 原子累加一个视频指定维度的已汇总快照数值（利用底层数据库 ON DUPLICATE KEY UPDATE 机制，并做非负保护）。
     *
     * @param vid 视频公开编码
     * @param type 计数维度类型
     * @param delta 净变动量
     */
    void applyDelta(String vid, CounterType type, long delta);
}
