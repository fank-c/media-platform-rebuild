package com.calles.platform.content.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.content.infrastructure.persistence.entity.VideoTagRelPO;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 视频与标签关联数据访问 Mapper。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：持久化访问 {@code video_tag_rel} 中间关系表；</li>
 *   <li><b>功能覆盖</b>：负责按视频 ID 拉取关联标签、按标签 ID 倒排检索关联视频，以及批量解除关系。</li>
 * </ul>
 * </p>
 */
@Mapper
public interface VideoTagRelMapper extends BaseMapper<VideoTagRelPO> {

    /**
     * 正向查询：根据视频内部 ID 查询其绑定的全部标签 ID。
     *
     * @param videoId 视频全局主键 ID (UUID)
     * @return 关联的标签主键 ID 列表（按打标时间正序排列）
     */
    @Select("SELECT tag_id FROM video_tag_rel WHERE video_id = #{videoId} ORDER BY created_at ASC")
    List<String> selectTagIdsByVideoId(@Param("videoId") String videoId);

    /**
     * 倒排查询：根据标签 ID 分页拉取关联的视频 ID 列表。
     *
     * @param tagId 标签全局主键 ID (UUID)
     * @param offset 偏移量
     * @param limit 本次查询上限
     * @return 关联的视频主键 ID 列表（按创建时间倒序排列）
     */
    @Select("SELECT video_id FROM video_tag_rel WHERE tag_id = #{tagId} ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<String> selectVideoIdsByTagId(@Param("tagId") String tagId, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 物理删除指定视频的所有标签关联关系记录。
     *
     * @param videoId 视频全局主键 ID (UUID)
     * @return 物理删除的行数
     */
    @Delete("DELETE FROM video_tag_rel WHERE video_id = #{videoId}")
    int deleteByVideoId(@Param("videoId") String videoId);

    /**
     * 批量持久化插入多条视频与标签关联绑定记录。
     *
     * @param list 关联持久化实体列表
     * @return 成功插入的记录行数
     */
    @Insert("""
            <script>
            INSERT INTO video_tag_rel (id, video_id, tag_id, created_at)
            VALUES
            <foreach collection='list' item='item' separator=','>
                (#{item.id}, #{item.videoId}, #{item.tagId}, CURRENT_TIMESTAMP(3))
            </foreach>
            </script>
            """)
    int batchInsert(@Param("list") List<VideoTagRelPO> list);

    /**
     * 精准批量物理删除指定视频名下的部分特定标签关联记录。
     *
     * @param videoId 视频全局主键 ID (UUID)
     * @param tagIds 待解绑的标签主键 ID 集合
     * @return 物理删除的行数
     */
    @Delete("""
            <script>
            DELETE FROM video_tag_rel
            WHERE video_id = #{videoId} AND tag_id IN
            <foreach item='tagId' collection='tagIds' open='(' separator=',' close=')'>
                #{tagId}
            </foreach>
            </script>
            """)
    int deleteByVideoIdAndTagIds(@Param("videoId") String videoId, @Param("tagIds") Collection<String> tagIds);
}
