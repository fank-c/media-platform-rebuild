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

    /**
     * 按视频内部全局 ID 查询单条记录。
     *
     * @param videoId 视频内部 ID
     * @return 匹配的 PO，未匹配返回 null
     */
    @Select("SELECT * FROM recommend_candidate_video WHERE video_id = #{videoId} LIMIT 1")
    CandidateVideoPO selectByVideoId(@Param("videoId") String videoId);

    /**
     * 按业务公开短码 vid 查询单条记录。
     *
     * @param vid 视频业务短码
     * @return 匹配的 PO，未匹配返回 null
     */
    @Select("SELECT * FROM recommend_candidate_video WHERE vid = #{vid} LIMIT 1")
    CandidateVideoPO selectByVid(@Param("vid") String vid);

    /**
     * 幂等插入候选视频；若已存在唯一键冲突 (uk_rcv_video_id) 则安全忽略。
     *
     * @param po 候选持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT IGNORE INTO recommend_candidate_video
            (id, video_id, vid, author_id, domain_tag_ids, topic_tag_ids, status, published_at, created_at, updated_at)
            VALUES
            (#{id}, #{videoId}, #{vid}, #{authorId}, #{domainTagIds}, #{topicTagIds}, #{status}, #{publishedAt}, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            """)
    int insertIgnore(CandidateVideoPO po);

    /**
     * 原子更新指定视频的生命周期状态。
     *
     * @param videoId 视频内部 ID
     * @param status 目标状态字符串 (ACTIVE, OFFLINE, BANNED)
     * @return 影响行数
     */
    @Update("""
            UPDATE recommend_candidate_video
            SET status = #{status}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE video_id = #{videoId}
            """)
    int updateStatusByVideoId(@Param("videoId") String videoId, @Param("status") String status);
}
