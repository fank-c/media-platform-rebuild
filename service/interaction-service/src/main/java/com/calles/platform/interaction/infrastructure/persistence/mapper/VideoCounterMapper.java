package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoCounterPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 视频互动统计计数持久层 Mapper 接口。
 */
@Mapper
public interface VideoCounterMapper extends BaseMapper<VideoCounterPO> {

    /**
     * 原子增加播放量（若记录不存在则自动插入并初始化）。
     *
     * @param vid 视频编码
     * @param delta 增量
     * @return 影响行数
     */
    @Update("""
            INSERT INTO interaction_video_counter (vid, view_count, created_at, updated_at)
            VALUES (#{vid}, #{delta}, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                view_count = view_count + #{delta},
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int incrementViewCount(@Param("vid") String vid, @Param("delta") long delta);

    /**
     * 原子调整点赞计数（确保不为负，若记录不存在则自动插入）。
     *
     * @param vid 视频编码
     * @param delta 增减量 (+1 或 -1)
     * @return 影响行数
     */
    @Update("""
            INSERT INTO interaction_video_counter (vid, like_count, created_at, updated_at)
            VALUES (#{vid}, GREATEST(0, #{delta}), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                like_count = GREATEST(0, like_count + #{delta}),
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int adjustLikeCount(@Param("vid") String vid, @Param("delta") long delta);

    /**
     * 原子调整收藏计数（确保不为负，若记录不存在则自动插入）。
     *
     * @param vid 视频编码
     * @param delta 增减量 (+1 或 -1)
     * @return 影响行数
     */
    @Update("""
            INSERT INTO interaction_video_counter (vid, star_count, created_at, updated_at)
            VALUES (#{vid}, GREATEST(0, #{delta}), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                star_count = GREATEST(0, star_count + #{delta}),
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int adjustStarCount(@Param("vid") String vid, @Param("delta") long delta);

    /**
     * 原子增加分享计数。
     *
     * @param vid 视频编码
     * @param delta 增量
     * @return 影响行数
     */
    @Update("""
            INSERT INTO interaction_video_counter (vid, share_count, created_at, updated_at)
            VALUES (#{vid}, #{delta}, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                share_count = share_count + #{delta},
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int incrementShareCount(@Param("vid") String vid, @Param("delta") long delta);
}
