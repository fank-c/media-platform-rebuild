package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.InteractionCounterDeltaPO;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 视频互动统计增量持久层 Mapper 接口。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}，提供待汇总增量的顺序悲观锁锁定、批量标记处理以及过期清理能力。</p>
 */
@Mapper
public interface InteractionCounterDeltaMapper extends BaseMapper<InteractionCounterDeltaPO> {

    /**
     * 按 ID 自增顺序悲观锁定待汇总增量记录（当前事务独占锁）。
     *
     * @param limit 单次批处理最大领取行数
     * @return 锁定的待汇总增量持久化对象列表
     */
    @Select("""
            SELECT id, vid, counter_type, delta, source_type, source_id, created_at, processed_at
            FROM interaction_counter_delta
            WHERE processed_at IS NULL
            ORDER BY id ASC
            LIMIT #{limit}
            FOR UPDATE
            """)
    List<InteractionCounterDeltaPO> lockPendingForUpdate(@Param("limit") int limit);

    /**
     * 批量标记增量记录的汇总完成时间戳。
     *
     * @param ids 待标记记录主键集合
     * @param processedAt 汇总完成时间
     * @return 实际影响行数
     */
    @Update("""
            <script>
            UPDATE interaction_counter_delta
            SET processed_at = #{processedAt}
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    int markProcessedBatch(@Param("ids") Collection<Long> ids, @Param("processedAt") LocalDateTime processedAt);

    /**
     * 物理清理超过保留期且已完成汇总的历史增量数据。
     *
     * @param threshold 保留期截止时间点
     * @param limit 单次最大删除行数
     * @return 实际删除行数
     */
    @Delete("""
            DELETE FROM interaction_counter_delta
            WHERE processed_at IS NOT NULL
              AND processed_at < #{threshold}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    int deleteProcessedBefore(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);
}
