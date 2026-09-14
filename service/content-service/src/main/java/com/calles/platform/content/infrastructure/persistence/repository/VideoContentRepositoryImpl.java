package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.video.VideoContent;
import com.calles.platform.content.domain.repository.VideoContentRepository;
import com.calles.platform.content.infrastructure.persistence.entity.VideoContentPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoContentMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频内容仓储端口实现。
 */
@Repository
@RequiredArgsConstructor
public class VideoContentRepositoryImpl implements VideoContentRepository {

    private final VideoContentMapper videoContentMapper;

    @Override
    public int insert(VideoContent video) {
        VideoContentPO po = VideoContentPO.fromDomain(video);
        if (po.getRevision() == null) {
            po.setRevision(0L);
        }
        return videoContentMapper.insert(po);
    }

    @Override
    public int updateById(VideoContent video) {
        VideoContentPO po = VideoContentPO.fromDomain(video);
        return videoContentMapper.updateWithOptimisticLock(po);
    }

    @Override
    public Optional<VideoContent> findById(String id) {
        VideoContentPO po = videoContentMapper.selectById(id);
        return Optional.ofNullable(po).map(VideoContentPO::toDomain);
    }

    @Override
    public Optional<VideoContent> findByVid(String vid) {
        VideoContentPO po = videoContentMapper.selectByVid(vid);
        return Optional.ofNullable(po).map(VideoContentPO::toDomain);
    }

    @Override
    public int deleteById(String id) {
        return videoContentMapper.deleteById(id);
    }
}
