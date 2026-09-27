package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.watch.WatchHistoryEntry;
import com.calles.platform.interaction.domain.model.watch.WatchProgress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 观看进度仓储接口。
 */
public interface WatchProgressRepository {

    /**
     * 以行级排他锁读取观看进度，作为心跳事务的并发串行化入口。
     *
     * @param userId 用户 ID
     * @param vid 视频公开短码
     * @return 观看进度；无记录时返回空
     */
    Optional<WatchProgress> lockByUserAndVid(String userId, String vid);

    /**
     * 只读查询观看进度（含已隐藏记录），供断点查询与状态核查使用。
     *
     * @param userId 用户 ID
     * @param vid 视频公开短码
     * @return 观看进度；无记录时返回空
     */
    Optional<WatchProgress> findByUserAndVid(String userId, String vid);

    /**
     * 写入全新的观看进度记录。
     *
     * @param progress 观看进度实体
     */
    void insert(WatchProgress progress);

    /**
     * 更新心跳产生的断点、累计时长与活跃会话。
     *
     * @param progress 观看进度实体
     */
    void updateHeartbeat(WatchProgress progress);

    /**
     * 标记最近一次成功计入播放量的时间。
     *
     * @param id 记录主键
     * @param now 计入时间
     */
    void markViewClaimed(String id, LocalDateTime now);

    /**
     * 分页查询用户历史展示列表（仅未隐藏记录，按最近心跳倒序，并带上视频时长快照）。
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 观看历史展示条目列表
     */
    List<WatchHistoryEntry> findVisibleHistoryPage(String userId, int offset, int limit);

    /**
     * 仅隐藏单条历史展示。
     *
     * @param userId 用户 ID
     * @param vid 视频公开短码
     * @return 影响行数
     */
    int hideByUserAndVid(String userId, String vid);

    /**
     * 仅隐藏用户全部历史展示。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    int hideAllByUserId(String userId);

    /**
     * 解除长期无心跳的活跃会话引用。
     *
     * @param threshold 视为不活跃的时间边界
     * @param limit 单次最多处理行数
     * @return 影响行数
     */
    int detachStaleActiveSessions(LocalDateTime threshold, int limit);

    /**
     * 物理删除保留期外且已隐藏的历史展示记录。
     *
     * @param threshold 保留期截止时间点
     * @param limit 单次最多删除行数
     * @return 影响行数
     */
    int deleteHiddenBefore(LocalDateTime threshold, int limit);
}
