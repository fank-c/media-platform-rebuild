package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchSessionPO;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 观看会话持久层 Mapper 接口。
 */
@Mapper
public interface WatchSessionMapper extends BaseMapper<WatchSessionPO> {

    /**
     * 写入新的观看会话。
     *
     * @param po 待插入的持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO interaction_watch_session
                (session_id, user_id, vid, duration_snapshot, qualification_threshold, credited_duration,
                 last_sequence, last_position, qualified, view_counted_at, start_request_key,
                 started_at, last_heartbeat_at, closed_at)
            VALUES
                (#{po.sessionId}, #{po.userId}, #{po.vid}, #{po.durationSnapshot}, #{po.qualificationThreshold},
                 #{po.creditedDuration}, #{po.lastSequence}, #{po.lastPosition}, #{po.qualified},
                 #{po.viewCountedAt}, #{po.startRequestKey},
                 #{po.startedAt}, #{po.lastHeartbeatAt}, #{po.closedAt})
            """)
    int insertSession(@Param("po") WatchSessionPO po);

    /**
     * 按起播请求幂等键查询指定用户和视频的历史会话。
     *
     * @param userId 用户 ID
     * @param vid 视频公开短码
     * @param startRequestKey 起播幂等键
     * @return 匹配的观看会话持久化对象，不存在时返回 null
     */
    @Select("""
            SELECT * FROM interaction_watch_session
            WHERE user_id = #{userId} AND vid = #{vid} AND start_request_key = #{startRequestKey}
            LIMIT 1
            """)
    WatchSessionPO selectByStartRequestKey(@Param("userId") String userId,
                                          @Param("vid") String vid,
                                          @Param("startRequestKey") String startRequestKey);

    /**
     * 更新会话累计时长、序号、断点与资格状态。
     *
     * @param po 已包含本次心跳结果的持久化对象
     * @return 影响行数
     */
    @Update("""
            UPDATE interaction_watch_session SET
                credited_duration = #{po.creditedDuration},
                last_sequence = #{po.lastSequence},
                last_position = #{po.lastPosition},
                qualified = #{po.qualified},
                last_heartbeat_at = #{po.lastHeartbeatAt}
            WHERE session_id = #{po.sessionId}
            """)
    int updateHeartbeat(@Param("po") WatchSessionPO po);

    /**
     * 关闭会话并写入关闭时间。
     *
     * @param sessionId 会话主键
     * @param now 关闭时间
     * @return 影响行数
     */
    @Update("""
            UPDATE interaction_watch_session SET closed_at = #{now}
            WHERE session_id = #{sessionId} AND closed_at IS NULL
            """)
    int closeSession(@Param("sessionId") String sessionId, @Param("now") LocalDateTime now);

    /**
     * 物理删除保留期外的历史会话，仅由数据清理任务调用。
     *
     * <p>安全引用保护：通过 NOT EXISTS 确保仅删除未被任何观看进度记录引用（active_session_id 为空或未指向当前会话）的会话，
     * 防止超期数据量超过批大小时因批次挑选错位导致仍被引用的会话被提前删除留下悬空引用。</p>
     *
     * @param threshold 保留期截止时间点
     * @param limit 单次最多删除行数
     * @return 影响行数
     */
    @Delete("""
            DELETE FROM interaction_watch_session
            WHERE last_heartbeat_at < #{threshold}
              AND NOT EXISTS (
                  SELECT 1 FROM interaction_watch_progress p
                  WHERE p.active_session_id = interaction_watch_session.session_id
              )
            ORDER BY last_heartbeat_at ASC
            LIMIT #{limit}
            """)
    int deleteStaleBefore(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);
}
