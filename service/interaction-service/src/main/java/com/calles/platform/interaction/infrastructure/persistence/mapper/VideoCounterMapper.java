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

    /**
     * 异步批量刷盘绝对值快照（Write-Behind 核心落地 SQL）。
     *
     * <p>利用 MySQL {@code ON DUPLICATE KEY UPDATE} 机制：
     * 若记录不存在则执行初始化插入；若已存在则直接以缓存计算出的最新绝对计数值覆盖各维度列，
     * 消除高并发增量下的行级写锁冲突与死锁风险。</p>
     *
     * @param po 包含视频短码及最新各维度绝对计数的持久化实体（vid 不可为空）
     * @return 数据库受影响行数（全新插入返回 1，存在且数据变动更新返回 2，无变动返回 0）
     */
    @Update("""
            INSERT INTO interaction_video_counter (vid, view_count, like_count, star_count, share_count, comment_count, created_at, updated_at)
            VALUES (#{po.vid}, #{po.viewCount}, #{po.likeCount}, #{po.starCount}, #{po.shareCount}, #{po.commentCount}, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                view_count = #{po.viewCount},
                like_count = #{po.likeCount},
                star_count = #{po.starCount},
                share_count = #{po.shareCount},
                comment_count = #{po.commentCount},
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int upsertSnapshot(@Param("po") VideoCounterPO po);
}
