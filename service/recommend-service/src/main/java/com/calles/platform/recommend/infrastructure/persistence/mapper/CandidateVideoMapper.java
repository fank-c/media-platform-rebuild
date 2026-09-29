package com.calles.platform.recommend.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.recommend.infrastructure.persistence.entity.CandidateVideoPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 推荐候选池 MyBatis-Plus 数据访问 Mapper。
 */
@Mapper
public interface CandidateVideoMapper extends BaseMapper<CandidateVideoPO> {

    /** 按视频内部全局 ID 查询单条记录。 */
    @Select("SELECT * FROM recommend_candidate_video WHERE video_id = #{videoId} LIMIT 1")
    CandidateVideoPO selectByVideoId(@Param("videoId") String videoId);

    /** 按业务公开短码查询单条记录。 */
    @Select("SELECT * FROM recommend_candidate_video WHERE vid = #{vid} LIMIT 1")
    CandidateVideoPO selectByVid(@Param("vid") String vid);

    /** 幂等插入候选视频。 */
    @Insert("""
            INSERT IGNORE INTO recommend_candidate_video
            (id, video_id, vid, author_id, domain_tag_ids, topic_tag_ids, status, published_at, created_at, updated_at)
            VALUES
            (#{id}, #{videoId}, #{vid}, #{authorId}, #{domainTagIds}, #{topicTagIds}, #{status}, #{publishedAt}, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            """)
    int insertIgnore(CandidateVideoPO po);

    /** 原子更新指定视频生命周期状态。 */
    @Update("""
            UPDATE recommend_candidate_video
            SET status = #{status}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE video_id = #{videoId}
            """)
    int updateStatusByVideoId(@Param("videoId") String videoId, @Param("status") String status);

    /** 查询最新 ACTIVE 候选，用于冷启动与候选池补齐。 */
    @Select("""
            SELECT id, video_id, vid, author_id, domain_tag_ids, topic_tag_ids,
                   status, published_at, created_at, updated_at
            FROM recommend_candidate_video
            WHERE status = 'ACTIVE'
            ORDER BY published_at DESC, video_id ASC
            LIMIT #{limit}
            """)
    java.util.List<CandidateVideoPO> selectRecentActive(@Param("limit") int limit);

    /**
     * 按作者、状态和固定时间窗口查询候选。
     *
     * @param authorIds 作者账号 ID 集合
     * @param windowStart 发布时间下界（包含）
     * @param anchorTime 发布时间上界（包含）
     * @param limit 返回上限
     * @return 匹配的候选 PO 列表
     */
    @Select("""
            <script>
            SELECT id, video_id, vid, author_id, domain_tag_ids, topic_tag_ids,
                   status, published_at, created_at, updated_at
            FROM recommend_candidate_video
            WHERE author_id IN
              <foreach collection="authorIds" item="authorId" open="(" separator="," close=")">
                  #{authorId}
              </foreach>
              AND status = 'ACTIVE'
              AND published_at >= #{windowStart}
              AND published_at <= #{anchorTime}
            ORDER BY published_at DESC, video_id ASC
            LIMIT #{limit}
            </script>
            """)
    java.util.List<CandidateVideoPO> selectRecentActiveByAuthorIds(
            @Param("authorIds") java.util.List<String> authorIds,
            @Param("windowStart") java.time.LocalDateTime windowStart,
            @Param("anchorTime") java.time.LocalDateTime anchorTime,
            @Param("limit") int limit);
}
