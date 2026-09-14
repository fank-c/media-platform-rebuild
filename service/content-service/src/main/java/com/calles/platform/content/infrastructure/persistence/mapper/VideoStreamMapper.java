package com.calles.platform.content.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.content.infrastructure.persistence.entity.VideoStreamPO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 视频转码流数据访问 Mapper。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：持久化访问 {@code video_stream} 数据表；</li>
 *   <li><b>功能覆盖</b>：提供按视频 ID 查询流清单、按 (videoId, quality, format) 规格精确查找及视频级联物理清理。</li>
 * </ul>
 * </p>
 */
@Mapper
public interface VideoStreamMapper extends BaseMapper<VideoStreamPO> {

    /**
     * 查询指定视频所属的全部清晰度转码流切片记录。
     *
     * @param videoId 视频内部主键 ID (UUID)
     * @return 按文件体积由大到小排序的流持久化实体列表
     */
    @Select("SELECT * FROM video_stream WHERE video_id = #{videoId} ORDER BY file_size DESC")
    List<VideoStreamPO> selectByVideoId(@Param("videoId") String videoId);

    /**
     * 根据画质规格与流媒体封装格式精确查询单条切片记录。
     *
     * @param videoId 视频主键 ID
     * @param quality 画质档位字面量 (如 "1080P", "720P")
     * @param format 封装格式字面量 (如 "MP4", "HLS")
     * @return 匹配的转码流持久化实体，未找到时返回 null
     */
    @Select("SELECT * FROM video_stream WHERE video_id = #{videoId} AND quality = #{quality} AND format = #{format} LIMIT 1")
    VideoStreamPO selectBySpec(@Param("videoId") String videoId, @Param("quality") String quality, @Param("format") String format);

    /**
     * 物理删除指定视频名下的所有转码流切片记录。
     *
     * @param videoId 视频内部主键 ID
     * @return 成功删除的记录行数
     */
    @Delete("DELETE FROM video_stream WHERE video_id = #{videoId}")
    int deleteByVideoId(@Param("videoId") String videoId);
}
