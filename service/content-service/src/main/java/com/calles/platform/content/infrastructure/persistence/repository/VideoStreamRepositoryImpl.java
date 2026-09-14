package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.stream.StreamFormat;
import com.calles.platform.content.domain.model.stream.StreamQuality;
import com.calles.platform.content.domain.model.stream.VideoStream;
import com.calles.platform.content.domain.repository.VideoStreamRepository;
import com.calles.platform.content.infrastructure.persistence.entity.VideoStreamPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoStreamMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频转码流仓储实现类 (VideoStreamRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施层针对流媒体切片实体 {@link VideoStream} 的持久化适配；</li>
 *   <li><b>协作对象</b>：委托 {@link VideoStreamMapper} 与底层数据库表 {@code video_stream} 交互；</li>
 *   <li><b>特性</b>：负责 PO 与领域模型的互转，新增时回写生成的主键 ID。</li>
 * </ul>
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class VideoStreamRepositoryImpl implements VideoStreamRepository {

    /** 视频转码流底层 Mapper。 */
    private final VideoStreamMapper videoStreamMapper;

    @Override
    public int insert(VideoStream stream) {
        // 步骤 1：领域实体映射为 PO
        VideoStreamPO po = VideoStreamPO.fromDomain(stream);
        int rows = videoStreamMapper.insert(po);
        // 步骤 2：回写主键 ID 到领域实体中
        if (rows > 0 && po.getId() != null) {
            stream.setId(po.getId());
        }
        return rows;
    }

    @Override
    public List<VideoStream> findByVideoId(String videoId) {
        // 步骤 1：按视频内部 ID 查询该视频下的所有流切片并转换为领域模型
        return videoStreamMapper.selectByVideoId(videoId).stream()
                .map(VideoStreamPO::toDomain)
                .toList();
    }

    @Override
    public Optional<VideoStream> findBySpec(String videoId, StreamQuality quality, StreamFormat format) {
        // 步骤 1：按规格复合键检索单条切片
        VideoStreamPO po = videoStreamMapper.selectBySpec(videoId, quality.getValue(), format.getValue());
        // 步骤 2：转换为领域实体
        return Optional.ofNullable(po).map(VideoStreamPO::toDomain);
    }

    @Override
    public int deleteByVideoId(String videoId) {
        // 步骤 1：按视频 ID 级联清理所有转码流记录
        return videoStreamMapper.deleteByVideoId(videoId);
    }
}
