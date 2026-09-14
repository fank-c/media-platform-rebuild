package com.calles.platform.content.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.content.infrastructure.persistence.entity.VideoContentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 视频内容 MyBatis-Plus 数据访问 Mapper。
 */
@Mapper
public interface VideoContentMapper extends BaseMapper<VideoContentPO> {

    /**
     * 忽略逻辑删除条件查询物理记录，仅供管理后台或恢复判断。
     */
    @Select("SELECT * FROM video_content WHERE id = #{id} LIMIT 1")
    VideoContentPO selectPhysicalById(@Param("id") String id);

    /**
     * 根据业务公开编码 vid 查询有效视频。
     */
    @Select("SELECT * FROM video_content WHERE vid = #{vid} AND deleted = 0 LIMIT 1")
    VideoContentPO selectByVid(@Param("vid") String vid);

    /**
     * 带乐观锁 revision 的字段更新。
     */
    @Update("""
            UPDATE video_content SET
                title = #{po.title},
                description = #{po.description},
                cover_file_id = #{po.coverFileId},
                duration = #{po.duration},
                tags = #{po.tags},
                status = #{po.status},
                publish_status = #{po.publishStatus},
                reject_reason = #{po.rejectReason},
                visibility = #{po.visibility},
                published_at = #{po.publishedAt},
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{po.id} AND revision = #{po.revision} AND deleted = 0
            """)
    int updateWithOptimisticLock(@Param("po") VideoContentPO po);

    /**
     * 异步更新互动计数快照，不递增业务 revision。
     */
    @Update("""
            UPDATE video_content SET
                view_count = #{viewCount},
                like_count = #{likeCount},
                comment_count = #{commentCount},
                star_count = #{starCount},
                share_count = #{shareCount},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id} AND deleted = 0
            """)
    int updateMetricsSnapshot(@Param("id") String id, @Param("viewCount") long viewCount,
                              @Param("likeCount") long likeCount, @Param("commentCount") long commentCount,
                              @Param("starCount") long starCount, @Param("shareCount") long shareCount);
}
