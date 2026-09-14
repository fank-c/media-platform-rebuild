package com.calles.platform.content.domain.repository;

import com.calles.platform.content.domain.model.stream.StreamFormat;
import com.calles.platform.content.domain.model.stream.StreamQuality;
import com.calles.platform.content.domain.model.stream.VideoStream;
import java.util.List;
import java.util.Optional;

/**
 * 视频转码流仓储端口接口。
 */
public interface VideoStreamRepository {

    /**
     * 保存转码流资产记录。
     *
     * @param stream 视频流实体
     * @return 影响行数
     */
    int insert(VideoStream stream);

    /**
     * 查询指定视频的所有已生成转码流。
     *
     * @param videoId 视频内部 ID
     * @return 该视频下的所有流资产列表
     */
    List<VideoStream> findByVideoId(String videoId);

    /**
     * 精确查询指定视频、画质与封装格式的流文件。
     *
     * @param videoId 视频 ID
     * @param quality 画质
     * @param format 封装格式
     * @return 对应流实体（若存在）
     */
    Optional<VideoStream> findBySpec(String videoId, StreamQuality quality, StreamFormat format);

    /**
     * 删除指定视频关联的所有转码流记录。
     *
     * @param videoId 视频 ID
     * @return 影响行数
     */
    int deleteByVideoId(String videoId);
}
