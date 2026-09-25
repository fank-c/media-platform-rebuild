package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 用户视频观看历史与断点仓储接口。
 */
public interface WatchHistoryRepository {

    /**
     * 查询指定用户针对特定视频的有效观看历史与进度（排除已逻辑删除记录）。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return 观看历史实体
     */
    Optional<WatchHistory> findByUserAndVid(String userId, String vid);

    /**
     * 物理查询指定用户针对特定视频的观看历史（包含逻辑删除记录，用于心跳防重与断点自愈）。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return 观看历史实体 (包含 deleted 状态)
     */
    Optional<WatchHistory> findPhysicalByUserAndVid(String userId, String vid);

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
     * 自愈复活已逻辑删除的观看历史记录，更新心跳与断点并置位有效状态。
     *
     * @param history 待复活的观看实体
     */
    void revive(WatchHistory history);

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

    /**
     * 原子 CAS 更新完播状态，防止并发心跳重复发布完播事件。
     *
     * @param id 主键 ID
     * @return 实际影响行数（1=成功从未完播转为完播，0=原本已是完播）
     */
    int markCompletedIfUncompleted(String id);

    /**
     * 原子抢占首次有效播放资格并更新 last_valid_play_at 时间戳与 session_play_emitted 标记。
     *
     * @param id 观看历史记录主键 ID
     * @param now 当前时间戳
     * @param validThreshold 达成有效播放所需的当前会话最低有效观看秒数
     * @return 实际影响行数（1=成功抢占，0=条件不符或已被抢占）
     */
    int claimInitialPlay(String id, LocalDateTime now, int validThreshold);

    /**
     * 原子抢占再次有效播放资格并更新 last_valid_play_at 时间戳与 session_play_emitted 标记。
     *
     * @param id 观看历史记录主键 ID
     * @param now 当前时间戳
     * @param cooldownBoundary 冷却时间边界 (now - repeatWindow)
     * @param validThreshold 达成有效播放所需的当前会话最低有效观看秒数
     * @return 实际影响行数（1=成功抢占，0=仍在冷却期、资格不符或已被抢占）
     */
    int claimRepeatPlay(String id, LocalDateTime now, LocalDateTime cooldownBoundary, int validThreshold);

    /**
     * 原子抢占当前冷却周期的有效播放资格并更新 last_valid_play_at 时间戳。
     *
     * @param id 观看历史记录主键 ID
     * @param now 当前时间戳
     * @param cooldownBoundary 冷却时间边界 (now - repeatWindow)
     * @return 实际影响行数（1=抢占成功，0=仍在冷却期内或已被其他并发请求抢先处理）
     */
    int claimValidPlay(String id, LocalDateTime now, LocalDateTime cooldownBoundary);
}
