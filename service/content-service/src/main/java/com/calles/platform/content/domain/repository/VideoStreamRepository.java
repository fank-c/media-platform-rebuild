package com.calles.platform.content.domain.repository;

import com.calles.platform.content.domain.model.stream.StreamFormat;
import com.calles.platform.content.domain.model.stream.StreamQuality;
import com.calles.platform.content.domain.model.stream.VideoStream;
import java.util.List;
import java.util.Optional;

/**
 * 视频转码流仓储端口接口 (Domain Repository Interface)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：转码流媒体切片及画质规格资产的持久化契约；</li>
 *   <li><b>协作对象</b>：由基础设施层 {@link com.calles.platform.content.infrastructure.persistence.repository.VideoStreamRepositoryImpl} 映射 {@code video_stream} 表；</li>
 *   <li><b>查询维度</b>：支持根据主视频 ID 汇总查询、以及根据 (videoId, quality, format) 复合规格精确查询。</li>
 * </ul>
 * </p>
 */
public interface VideoStreamRepository {

    /**
     * 持久化转码流媒体切片记录。
     *
     * @param stream 待保存的视频流领域实体
     * @return 影响的数据库记录行数
     */
    int insert(VideoStream stream);

    /**
     * 根据主视频内部 ID 查询该视频已生成的所有转码流切片。
     *
     * @param videoId 视频内部全局主键 ID
     * @return 对应的流媒体资产列表（按文件尺寸倒序）
     */
    List<VideoStream> findByVideoId(String videoId);

    /**
     * 根据视频 ID、画质规格与流媒体封装格式精确检索唯一切片。
     *
     * @param videoId 视频全局主键 ID
     * @param quality 画质规格 (如 1080P, 720P)
     * @param format 封装格式 (如 MP4, HLS)
     * @return 包含流实体的 {@link Optional}，未匹配时返回 empty
     */
    Optional<VideoStream> findBySpec(String videoId, StreamQuality quality, StreamFormat format);

    /**
     * 级联物理删除指定视频名下的全部转码流切片记录。
     *
     * @param videoId 视频全局主键 ID
     * @return 影响的数据库记录行数
     */
    int deleteByVideoId(String videoId);
}
