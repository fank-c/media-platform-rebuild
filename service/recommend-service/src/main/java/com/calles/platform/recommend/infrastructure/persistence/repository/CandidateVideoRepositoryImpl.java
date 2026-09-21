package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.infrastructure.persistence.entity.CandidateVideoPO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.CandidateVideoMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 推荐候选池持久化仓储实现类 (CandidateVideoRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施层持久化实现；</li>
 *   <li><b>防并发能力</b>：依赖 {@link CandidateVideoMapper#insertIgnore} 杜绝重复投递导致的唯一键异常；</li>
 *   <li><b>模型映射</b>：负责领域实体与数据库持久化 PO 的双向映射解耦。</li>
 * </ul>
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class CandidateVideoRepositoryImpl implements CandidateVideoRepository {

    private final CandidateVideoMapper candidateVideoMapper;

    @Override
    public int insert(CandidateVideo candidate) {
        if (candidate == null) {
            return 0;
        }
        CandidateVideoPO po = CandidateVideoPO.fromDomain(candidate);
        return candidateVideoMapper.insertIgnore(po);
    }

    @Override
    public Optional<CandidateVideo> findByVideoId(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return Optional.empty();
        }
        CandidateVideoPO po = candidateVideoMapper.selectByVideoId(videoId.trim());
        return Optional.ofNullable(po).map(CandidateVideoPO::toDomain);
    }

    @Override
    public Optional<CandidateVideo> findByVid(String vid) {
        if (vid == null || vid.isBlank()) {
            return Optional.empty();
        }
        CandidateVideoPO po = candidateVideoMapper.selectByVid(vid.trim());
        return Optional.ofNullable(po).map(CandidateVideoPO::toDomain);
    }

    @Override
    public int updateStatusByVideoId(String videoId, CandidateStatus status) {
        if (videoId == null || videoId.isBlank() || status == null) {
            return 0;
        }
        return candidateVideoMapper.updateStatusByVideoId(videoId.trim(), status.getCode());
    }
}
