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
 * 视频转码流仓储端口实现。
 */
@Repository
@RequiredArgsConstructor
public class VideoStreamRepositoryImpl implements VideoStreamRepository {

    private final VideoStreamMapper videoStreamMapper;

    @Override
    public int insert(VideoStream stream) {
        VideoStreamPO po = VideoStreamPO.fromDomain(stream);
        int rows = videoStreamMapper.insert(po);
        if (rows > 0 && po.getId() != null) {
            stream.setId(po.getId());
        }
        return rows;
    }

    @Override
    public List<VideoStream> findByVideoId(String videoId) {
        return videoStreamMapper.selectByVideoId(videoId).stream()
                .map(VideoStreamPO::toDomain)
                .toList();
    }

    @Override
    public Optional<VideoStream> findBySpec(String videoId, StreamQuality quality, StreamFormat format) {
        VideoStreamPO po = videoStreamMapper.selectBySpec(videoId, quality.getValue(), format.getValue());
        return Optional.ofNullable(po).map(VideoStreamPO::toDomain);
    }

    @Override
    public int deleteByVideoId(String videoId) {
        return videoStreamMapper.deleteByVideoId(videoId);
    }
}
