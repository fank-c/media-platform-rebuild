package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 视频互动统计聚合根仓储接口。
 */
public interface VideoCounterRepository {

    /**
     * 根据视频公开短码查询互动统计实体。
     *
     * @param vid 视频公开编码
     * @return 统计聚合根实体
     */
    Optional<VideoCounter> findByVid(String vid);

    /**
     * 批量查询多个视频的互动统计实体列表。
     *
     * @param vids 视频公开短码集合
     * @return 统计实体列表
     */
    List<VideoCounter> findByVids(Collection<String> vids);

    /**
     * 保存或更新计数器实体。
     *
     * @param counter 实体对象
     */
    void save(VideoCounter counter);

    /**
     * 原子增加播放量。
     *
     * @param vid 视频编码
     * @param delta 增加量
     */
    void incrementViewCount(String vid, long delta);

    /**
     * 原子调整点赞计数。
     *
     * @param vid 视频编码
     * @param delta 变动量 (+1 或 -1)
     */
    void adjustLikeCount(String vid, long delta);

    /**
     * 原子调整收藏计数。
     *
     * @param vid 视频编码
     * @param delta 变动量 (+1 或 -1)
     */
    void adjustStarCount(String vid, long delta);

    /**
     * 原子增加分享计数。
     *
     * @param vid 视频编码
     * @param delta 增加量
     */
    void incrementShareCount(String vid, long delta);
}
