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
     * 自愈复活已逻辑删除的记录，重置断点、心跳时间并置位 deleted = 0，同时严格保留或更新 last_valid_play_at。
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
}
