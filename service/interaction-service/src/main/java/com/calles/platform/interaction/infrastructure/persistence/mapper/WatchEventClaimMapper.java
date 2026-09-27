package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchEventClaimPO;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 观看事件凭据持久层 Mapper 接口。
 *
 * <p>唯一键 {@code uk_watch_event_claim (user_id, vid, session_id, event_type)} 是播放量与完播事件的最终防重依据，
 * 插入冲突时抛出 DuplicateKeyException，由应用服务判定为"凭据已被抢占"。</p>
 */
@Mapper
public interface WatchEventClaimMapper extends BaseMapper<WatchEventClaimPO> {

    /**
     * 抢占一条事件凭据。
     *
     * @param po 待插入的持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO interaction_watch_event_claim
                (id, user_id, vid, session_id, event_type, outbox_event_id, claimed_at)
            VALUES
                (#{po.id}, #{po.userId}, #{po.vid}, #{po.sessionId}, #{po.eventType}, #{po.outboxEventId}, #{po.claimedAt})
            """)
    int insertClaim(@Param("po") WatchEventClaimPO po);

    /**
     * 查询指定会话已抢占的凭据类型。
     *
     * @param userId 用户 ID
     * @param vid 视频公开短码
     * @param sessionId 会话 ID
     * @return 凭据类型字面量列表
     */
    @Select("""
            SELECT event_type FROM interaction_watch_event_claim
            WHERE user_id = #{userId} AND vid = #{vid} AND session_id = #{sessionId}
            """)
    List<String> selectClaimedEventTypes(@Param("userId") String userId,
                                         @Param("vid") String vid,
                                         @Param("sessionId") String sessionId);

    @Select("""
            <script>
            SELECT DISTINCT vid FROM interaction_watch_event_claim
            WHERE user_id = #{userId}
              AND event_type = #{eventType}
              AND vid IN
              <foreach collection="vids" item="vid" open="(" separator="," close=")">
                  #{vid}
              </foreach>
            </script>
            """)
    List<String> selectClaimedVids(@Param("userId") String userId,
                                  @Param("eventType") String eventType,
                                  @Param("vids") Collection<String> vids);

    /**
     * 回填同事务产生的 Outbox 事件 ID。
     *
     * @param id 凭据主键
     * @param outboxEventId Outbox 事件 ID
     * @return 影响行数
     */
    @Update("UPDATE interaction_watch_event_claim SET outbox_event_id = #{outboxEventId} WHERE id = #{id}")
    int attachOutboxEventId(@Param("id") String id, @Param("outboxEventId") String outboxEventId);

    /**
     * 物理删除保留期外的事件凭据，仅由数据清理任务调用。
     *
     * <p>保护约束：仅删除关联会话已从会话表中清理的历史凭据，仍在存活会话中的凭据不予删除，
     * 避免持续长会话因凭据超期被清理导致重复产生事件。</p>
     *
     * @param threshold 保留期截止时间点
     * @param limit 单次最多删除行数
     * @return 影响行数
     */
    @Delete("""
            DELETE FROM interaction_watch_event_claim
            WHERE claimed_at < #{threshold}
              AND NOT EXISTS (
                  SELECT 1 FROM interaction_watch_session s
                  WHERE s.session_id = interaction_watch_event_claim.session_id
              )
            ORDER BY claimed_at ASC
            LIMIT #{limit}
            """)
    int deleteBefore(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);
}
