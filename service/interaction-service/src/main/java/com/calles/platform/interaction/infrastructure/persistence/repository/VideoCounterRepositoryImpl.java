package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoCounterPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoCounterMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频互动统计聚合根仓储实现类。
 */
@Repository
@RequiredArgsConstructor
public class VideoCounterRepositoryImpl implements VideoCounterRepository {

    private final VideoCounterMapper mapper;

    @Override
    public Optional<VideoCounter> findByVid(String vid) {
        if (vid == null || vid.isBlank()) {
            return Optional.empty();
        }
        VideoCounterPO po = mapper.selectById(vid);
        return Optional.ofNullable(po).map(VideoCounterPO::toDomain);
    }

    @Override
    public List<VideoCounter> findByVids(Collection<String> vids) {
        if (vids == null || vids.isEmpty()) {
            return List.of();
        }
        List<VideoCounterPO> pos = mapper.selectBatchIds(vids);
        if (pos == null) {
            return List.of();
        }
        return pos.stream().map(VideoCounterPO::toDomain).toList();
    }

    @Override
    public void save(VideoCounter counter) {
        if (counter == null) {
            return;
        }
        VideoCounterPO po = VideoCounterPO.fromDomain(counter);
        VideoCounterPO exist = mapper.selectById(counter.getVid());
        if (exist == null) {
            mapper.insert(po);
        } else {
            mapper.updateById(po);
        }
    }

    @Override
    public void incrementViewCount(String vid, long delta) {
        if (vid != null && !vid.isBlank() && delta > 0) {
            mapper.incrementViewCount(vid, delta);
        }
    }

    @Override
    public void adjustLikeCount(String vid, long delta) {
        if (vid != null && !vid.isBlank() && delta != 0) {
            mapper.adjustLikeCount(vid, delta);
        }
    }

    @Override
    public void adjustStarCount(String vid, long delta) {
        if (vid != null && !vid.isBlank() && delta != 0) {
            mapper.adjustStarCount(vid, delta);
        }
    }

    @Override
    public void incrementShareCount(String vid, long delta) {
        if (vid != null && !vid.isBlank() && delta > 0) {
            mapper.incrementShareCount(vid, delta);
        }
    }
}
