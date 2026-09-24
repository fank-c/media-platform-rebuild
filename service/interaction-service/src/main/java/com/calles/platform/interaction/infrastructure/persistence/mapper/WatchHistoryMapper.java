package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchHistoryPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 观看历史与断点持久层 Mapper 接口。
 */
@Mapper
public interface WatchHistoryMapper extends BaseMapper<WatchHistoryPO> {

    /**
     * 忽略逻辑删除，物理检索用户在指定视频的观看历史（用于心跳续播与防重判定）。
     *
     * @param userId 用户 ID
     * @param vid 视频公开短码
     * @return 匹配的观看历史 PO，若不存在则为 null
     */
    @Select("SELECT * FROM interaction_watch_history WHERE user_id = #{userId} AND vid = #{vid} LIMIT 1")
    WatchHistoryPO selectPhysicalByUserAndVid(@Param("userId") String userId, @Param("vid") String vid);

    /**
     * 更新心跳断点与累计时长，严格隔离 completed 字段（完播状态唯一由 markCompletedIfUncompleted 原子维护）。
     *
     * @param po 观看历史 PO
     * @return 影响行数
     */
    @Update("""
            UPDATE interaction_watch_history SET
                last_position = #{po.lastPosition},
                watched_duration = #{po.watchedDuration},
                video_duration = #{po.videoDuration},
                last_watch_at = #{po.lastWatchAt}
            WHERE id = #{po.id}
            """)
    int updateHeartbeat(@Param("po") WatchHistoryPO po);

    /**
     * 自愈复活已逻辑删除的记录，重置断点、心跳时间并置位 deleted = 0。
     *
     * @param po 观看历史 PO
     * @return 影响行数
     */
    @Update("""
            UPDATE interaction_watch_history SET
                last_position = #{po.lastPosition},
                watched_duration = #{po.watchedDuration},
                video_duration = #{po.videoDuration},
                completed = #{po.completed},
                last_watch_at = #{po.lastWatchAt},
                deleted = 0
            WHERE id = #{po.id}
            """)
    int reviveAndHeartbeat(@Param("po") WatchHistoryPO po);

    /**
     * 原子 CAS 将完播状态从未完播 (0) 更新为完播 (1)，防止多端并发心跳导致完播事件双发。
     *
     * @param id 观看历史记录主键 ID
     * @return 实际影响行数（仅在从 0 改为 1 时返回 1，若已完播则返回 0）
     */
    @Update("UPDATE interaction_watch_history SET completed = 1 WHERE id = #{id} AND completed = 0")
    int markCompletedIfUncompleted(@Param("id") String id);
}
