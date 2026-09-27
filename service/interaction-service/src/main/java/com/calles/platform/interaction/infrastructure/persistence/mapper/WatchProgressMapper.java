package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchProgressHistoryRow;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchProgressPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 观看进度持久层 Mapper 接口。
 *
 * <p>并发控制约定：同一用户同一视频的心跳必须先用 {@link #selectForUpdate} 取行级排他锁，
 * 由此把"会话切换 + 时长累计 + 凭据抢占"串行化，避免依赖分布式锁。</p>
 */
@Mapper
public interface WatchProgressMapper extends BaseMapper<WatchProgressPO> {

    /**
     * 以行级排他锁读取观看进度，作为心跳事务的并发串行化入口。
     *
     * @param userId 用户 ID
     * @param vid 视频公开短码
     * @return 观看进度持久化对象，无记录时返回 null
     */
    @Select("""
            SELECT * FROM interaction_watch_progress
            WHERE user_id = #{userId} AND vid = #{vid}
            FOR UPDATE
            """)
    WatchProgressPO selectForUpdate(@Param("userId") String userId, @Param("vid") String vid);

    /**
     * 写入全新的观看进度记录。
     *
     * @param po 待插入的持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO interaction_watch_progress
                (id, user_id, vid, active_session_id, last_position, watched_duration,
                 first_watch_at, last_watch_at, last_view_claimed_at, deleted)
            VALUES
                (#{po.id}, #{po.userId}, #{po.vid}, #{po.activeSessionId}, #{po.lastPosition}, #{po.watchedDuration},
                 #{po.firstWatchAt}, #{po.lastWatchAt}, #{po.lastViewClaimedAt}, #{po.deleted})
            """)
    int insertProgress(@Param("po") WatchProgressPO po);

    /**
     * 更新心跳产生的断点、累计时长与活跃会话，并恢复历史展示状态。
     *
     * @param po 已包含本次心跳结果的持久化对象
     * @return 影响行数
     */
    @Update("""
            UPDATE interaction_watch_progress SET
                active_session_id = #{po.activeSessionId},
                last_position = #{po.lastPosition},
                watched_duration = #{po.watchedDuration},
                last_watch_at = #{po.lastWatchAt},
                deleted = 0
            WHERE id = #{po.id}
            """)
    int updateHeartbeat(@Param("po") WatchProgressPO po);

    /**
     * 标记最近一次成功计入播放量的时间，驱动重复播放冷却窗口。
     *
     * @param id 记录主键
     * @param now 计入时间
     * @return 影响行数
     */
    @Update("UPDATE interaction_watch_progress SET last_view_claimed_at = #{now} WHERE id = #{id}")
    int markViewClaimed(@Param("id") String id, @Param("now") LocalDateTime now);

    /**
     * 查询用户观看历史展示列表，并左连接本地时长快照返回视频时长。
     *
     * <p>只读取 interaction-service 自属的两张表，不做跳库查询；仅返回未隐藏记录。</p>
     *
     * @param userId 用户 ID
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 历史展示投影行列表
     */
    @Select("""
            SELECT p.id AS id,
                   p.vid AS vid,
                   p.last_position AS last_position,
                   p.watched_duration AS watched_duration,
                   p.first_watch_at AS first_watch_at,
                   p.last_watch_at AS last_watch_at,
                   COALESCE(s.duration, 0) AS video_duration
            FROM interaction_watch_progress p
            LEFT JOIN interaction_video_snapshot s ON s.vid = p.vid
            WHERE p.user_id = #{userId}
              AND p.deleted = 0
            ORDER BY p.last_watch_at DESC
            LIMIT #{offset}, #{limit}
            """)
    List<WatchProgressHistoryRow> selectVisibleHistoryPage(@Param("userId") String userId,
                                                           @Param("offset") int offset,
                                                           @Param("limit") int limit);

    /**
     * 仅隐藏用户对指定视频的历史展示，不删除记录、不释放任何防重状态。
     *
     * @param userId 用户 ID
     * @param vid 视频公开短码
     * @return 影响行数
     */
    @Update("""
            UPDATE interaction_watch_progress SET deleted = 1
            WHERE user_id = #{userId} AND vid = #{vid} AND deleted = 0
            """)
    int hideByUserAndVid(@Param("userId") String userId, @Param("vid") String vid);

    /**
     * 仅隐藏用户全部历史展示，不删除记录。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    @Update("""
            UPDATE interaction_watch_progress SET deleted = 1
            WHERE user_id = #{userId} AND deleted = 0
            """)
    int hideAllByUserId(@Param("userId") String userId);

    /**
     * 解除长期无心跳的活跃会话引用，为会话清理任务开路。
     *
     * @param threshold 视为不活跃的时间边界
     * @param limit 单次最多处理行数
     * @return 影响行数
     */
    @Update("""
            UPDATE interaction_watch_progress SET active_session_id = NULL
            WHERE last_watch_at < #{threshold}
              AND active_session_id IS NOT NULL
            ORDER BY last_watch_at ASC
            LIMIT #{limit}
            """)
    int detachStaleActiveSessions(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    /**
     * 物理删除观看进度记录，仅供数据清理任务在确认保留期后调用。
     *
     * <p>保护约束：必须同时满足已隐藏 (deleted=1)、无活跃会话引用、超过保留期且播放量冷却已结束，
     * 避免误删导致活跃播放失效或播放量冷却状态被提前释放。</p>
     *
     * @param threshold 保留期截止时间点
     * @param limit 单次最多删除行数
     * @return 影响行数
     */
    @Delete("""
            DELETE FROM interaction_watch_progress
            WHERE last_watch_at < #{threshold}
              AND deleted = 1
              AND active_session_id IS NULL
              AND (last_view_claimed_at IS NULL OR last_view_claimed_at < #{threshold})
            ORDER BY last_watch_at ASC
            LIMIT #{limit}
            """)
    int deleteHiddenBefore(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);
}
