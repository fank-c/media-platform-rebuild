package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import java.util.List;
import java.util.Optional;

/**
 * 用户视频观看历史与断点仓储接口。
 */
public interface WatchHistoryRepository {

    /**
     * 查询指定用户针对特定视频的观看历史与进度。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return 观看历史实体
     */
    Optional<WatchHistory> findByUserAndVid(String userId, String vid);

    /**
     * 分页查询用户的观看历史列表（按最后活跃时间倒序）。
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 观看历史列表
     */
    List<WatchHistory> findByUserId(String userId, int offset, int limit);

    /**
     * 统计用户的观看历史记录总数。
     *
     * @param userId 用户 ID
     * @return 记录条数
     */
    long countByUserId(String userId);

    /**
     * 保存新增观看历史。
     *
     * @param history 观看实体
     */
    void save(WatchHistory history);

    /**
     * 更新已有观看历史的心跳与断点数据。
     *
     * @param history 观看实体
     */
    void update(WatchHistory history);

    /**
     * 删除单条视频观看历史。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return 影响行数
     */
    int deleteByUserAndVid(String userId, String vid);

    /**
     * 清空用户所有观看历史。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    int deleteAllByUserId(String userId);
}
