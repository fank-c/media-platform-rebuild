package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoCounterPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 视频公开计数快照持久层 Mapper 接口。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}，并提供各维度计数的原子累加 SQL（基于 ON DUPLICATE KEY UPDATE 实现插入或累加，防负数下溢）。</p>
 */
@Mapper
public interface VideoCounterMapper extends BaseMapper<VideoCounterPO> {

    /**
     * 原子累加播放量快照。
     *
     * @param vid 视频公开短码
     * @param delta 播放量变动量
     * @return 数据库影响行数
     */
    @Update("""
            INSERT INTO interaction_video_counter (vid, view_count, created_at, updated_at)
            VALUES (#{vid}, GREATEST(0, #{delta}), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                view_count = GREATEST(0, view_count + #{delta}),
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int applyViewDelta(@Param("vid") String vid, @Param("delta") long delta);

    /**
     * 原子累加点赞数快照（非负防护）。
     *
     * @param vid 视频公开短码
     * @param delta 点赞变动量
     * @return 数据库影响行数
     */
    @Update("""
            INSERT INTO interaction_video_counter (vid, like_count, created_at, updated_at)
            VALUES (#{vid}, GREATEST(0, #{delta}), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                like_count = GREATEST(0, like_count + #{delta}),
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int applyLikeDelta(@Param("vid") String vid, @Param("delta") long delta);

    /**
     * 原子累加收藏数快照（非负防护）。
     *
     * @param vid 视频公开短码
     * @param delta 收藏变动量
     * @return 数据库影响行数
     */
    @Update("""
            INSERT INTO interaction_video_counter (vid, star_count, created_at, updated_at)
            VALUES (#{vid}, GREATEST(0, #{delta}), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                star_count = GREATEST(0, star_count + #{delta}),
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int applyStarDelta(@Param("vid") String vid, @Param("delta") long delta);

    /**
     * 原子累加分享数快照（非负防护）。
     *
     * @param vid 视频公开短码
     * @param delta 分享变动量
     * @return 数据库影响行数
     */
    @Update("""
            INSERT INTO interaction_video_counter (vid, share_count, created_at, updated_at)
            VALUES (#{vid}, GREATEST(0, #{delta}), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                share_count = GREATEST(0, share_count + #{delta}),
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int applyShareDelta(@Param("vid") String vid, @Param("delta") long delta);
}
