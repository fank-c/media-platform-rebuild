package com.calles.platform.content.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.content.infrastructure.persistence.entity.VideoContentPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 视频内容核心数据访问 Mapper。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：持久化访问 {@code video_content} 聚合根数据表；</li>
 *   <li><b>技术实现</b>：继承 MyBatis-Plus {@link BaseMapper}，并针对乐观锁 CAS 更新及互动指标快照回写定义原生高效 SQL；</li>
 *   <li><b>并发隔离</b>：利用 {@code revision = #{po.revision}} 保证多实例并发修改时的原子隔离。</li>
 * </ul>
 * </p>
 */
@Mapper
public interface VideoContentMapper extends BaseMapper<VideoContentPO> {

    /**
     * 忽略逻辑删除约束条件，按物理主键检索记录（仅供管理审计或数据恢复使用）。
     *
     * @param id 视频内部主键 ID (UUID)
     * @return 物理记录实体，无论 deleted 为 0 还是 1
     */
    @Select("SELECT * FROM video_content WHERE id = #{id} LIMIT 1")
    VideoContentPO selectPhysicalById(@Param("id") String id);

    /**
     * 根据对外公开的业务短码 vid 检索未逻辑删除的有效视频。
     *
     * @param vid 24 位高熵业务编码
     * @return 匹配的视频持久化对象，若未找到或已删除则返回 null
     */
    @Select("SELECT * FROM video_content WHERE vid = #{vid} AND deleted = 0 LIMIT 1")
    VideoContentPO selectByVid(@Param("vid") String vid);

    /**
     * 带乐观锁 revision 校验的业务属性更新。
     *
     * <p>仅当数据库中当前 revision 与入参 po.revision 一致且 deleted = 0 时才允许更新，并在更新成功后自增 revision。</p>
     *
     * @param po 待更新的视频持久化实体（包含旧 revision）
     * @return 影响行数；若为 0 说明发生了并发修改冲突或记录已被删除
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
     * 异步更新视频互动快照指标（不递增业务领域版本号 revision）。
     *
     * @param id 目标视频主键 ID
     * @param viewCount 播放量
     * @param likeCount 点赞量
     * @param commentCount 评论量
     * @param starCount 收藏量
     * @param shareCount 分享量
     * @return 影响行数
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
