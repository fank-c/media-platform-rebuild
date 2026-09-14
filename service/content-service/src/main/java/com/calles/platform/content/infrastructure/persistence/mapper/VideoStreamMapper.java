package com.calles.platform.content.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.content.infrastructure.persistence.entity.VideoStreamPO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 视频转码流 MyBatis-Plus 数据访问 Mapper。
 */
@Mapper
public interface VideoStreamMapper extends BaseMapper<VideoStreamPO> {

    /**
     * 查询指定视频的所有清晰度流记录。
     */
    @Select("SELECT * FROM video_stream WHERE video_id = #{videoId} ORDER BY file_size DESC")
    List<VideoStreamPO> selectByVideoId(@Param("videoId") String videoId);

    /**
     * 根据画质与封装格式查询单条流记录。
     */
    @Select("SELECT * FROM video_stream WHERE video_id = #{videoId} AND quality = #{quality} AND format = #{format} LIMIT 1")
    VideoStreamPO selectBySpec(@Param("videoId") String videoId, @Param("quality") String quality, @Param("format") String format);

    /**
     * 删除指定视频下的全部流记录。
     */
    @Delete("DELETE FROM video_stream WHERE video_id = #{videoId}")
    int deleteByVideoId(@Param("videoId") String videoId);
}
