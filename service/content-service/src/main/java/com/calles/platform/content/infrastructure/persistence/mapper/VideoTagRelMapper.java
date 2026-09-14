package com.calles.platform.content.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.content.infrastructure.persistence.entity.VideoTagRelPO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 视频与标签关联 MyBatis-Plus Mapper。
 */
@Mapper
public interface VideoTagRelMapper extends BaseMapper<VideoTagRelPO> {

    /**
     * 根据视频 ID 查询绑定的全部标签 ID。
     */
    @Select("SELECT tag_id FROM video_tag_rel WHERE video_id = #{videoId} ORDER BY created_at ASC")
    List<String> selectTagIdsByVideoId(@Param("videoId") String videoId);

    /**
     * 倒排查询：根据标签 ID 分页拉取关联的视频 ID 列表。
     */
    @Select("SELECT video_id FROM video_tag_rel WHERE tag_id = #{tagId} ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<String> selectVideoIdsByTagId(@Param("tagId") String tagId, @Param("offset") int offset, @Param("limit") int limit);

    /**
     * 删除指定视频的所有标签关联。
     */
    @Delete("DELETE FROM video_tag_rel WHERE video_id = #{videoId}")
    int deleteByVideoId(@Param("videoId") String videoId);
}
