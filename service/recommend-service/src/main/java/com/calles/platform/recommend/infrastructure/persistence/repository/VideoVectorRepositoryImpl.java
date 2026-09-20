package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.recommend.domain.model.VectorStatus;
import com.calles.platform.recommend.domain.model.VideoVector;
import com.calles.platform.recommend.domain.repository.VideoVectorRepository;
import com.calles.platform.recommend.infrastructure.persistence.entity.VideoVectorPO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.VideoVectorMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频向量领域仓储实现类 (VideoVectorRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施持久化层，防腐领域模型；</li>
 *   <li><b>模型转换</b>：负责 {@link VideoVector} 领域聚合根与 {@link VideoVectorPO} 数据库实体间的互转；</li>
 *   <li><b>单业务所有权</b>：严格操作 {@code recommend_video_vector} 表。</li>
 * </ul>
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class VideoVectorRepositoryImpl implements VideoVectorRepository {

    private final VideoVectorMapper mapper;

    @Override
    public Optional<VideoVector> findByVideoId(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<VideoVectorPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VideoVectorPO::getVideoId, videoId);
        VideoVectorPO po = mapper.selectOne(wrapper);
        return Optional.ofNullable(toDomain(po));
    }

    @Override
    public Optional<VideoVector> findByVid(String vid) {
        if (vid == null || vid.isBlank()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<VideoVectorPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VideoVectorPO::getVid, vid);
        VideoVectorPO po = mapper.selectOne(wrapper);
        return Optional.ofNullable(toDomain(po));
    }

    @Override
    public void save(VideoVector domain) {
        if (domain == null) {
            return;
        }
        VideoVectorPO po = toPO(domain);
        mapper.insert(po);
    }

    @Override
    public void update(VideoVector domain) {
        if (domain == null) {
            return;
        }
        VideoVectorPO po = toPO(domain);
        mapper.updateById(po);
    }

    private VideoVector toDomain(VideoVectorPO po) {
        if (po == null) {
            return null;
        }
        return new VideoVector(
                po.getId(),
                po.getVideoId(),
                po.getVid(),
                po.getModelName(),
                po.getDimension() != null ? po.getDimension() : 0,
                po.getVectorData(),
                po.getQdrantSynced() != null && po.getQdrantSynced() == 1,
                VectorStatus.fromCode(po.getStatus()),
                po.getErrorMessage(),
                po.getCreatedAt(),
                po.getUpdatedAt()
        );
    }

    private VideoVectorPO toPO(VideoVector domain) {
        VideoVectorPO po = new VideoVectorPO();
        po.setId(domain.getId());
        po.setVideoId(domain.getVideoId());
        po.setVid(domain.getVid());
        po.setModelName(domain.getModelName());
        po.setDimension(domain.getDimension());
        po.setVectorData(domain.getVectorData());
        po.setQdrantSynced(domain.isQdrantSynced() ? 1 : 0);
        po.setStatus(domain.getStatus() != null ? domain.getStatus().getCode() : VectorStatus.COMPLETED.getCode());
        po.setErrorMessage(domain.getErrorMessage());
        po.setCreatedAt(domain.getCreatedAt());
        po.setUpdatedAt(domain.getUpdatedAt());
        return po;
    }
}
