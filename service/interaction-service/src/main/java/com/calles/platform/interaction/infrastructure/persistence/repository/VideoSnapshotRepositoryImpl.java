package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import com.calles.platform.interaction.domain.repository.VideoSnapshotRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoSnapshotPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoSnapshotMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 视频元数据本地快照仓储实现类。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VideoSnapshotRepositoryImpl implements VideoSnapshotRepository {

    private final VideoSnapshotMapper mapper;

    @Override
    public Optional<VideoSnapshot> findByVid(String vid) {
        if (vid == null || vid.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(vid)).map(VideoSnapshotPO::toDomain);
    }

    @Override
    public boolean existsBySourceEventId(String sourceEventId) {
        if (sourceEventId == null || sourceEventId.isBlank()) {
            return false;
        }
        return mapper.countBySourceEventId(sourceEventId) > 0;
    }

    @Override
    public boolean saveIfNewerOrSameVersion(VideoSnapshot snapshot) {
        if (snapshot == null || snapshot.getVid() == null || snapshot.getVid().isBlank()) {
            return false;
        }

        VideoSnapshotPO po = VideoSnapshotPO.fromDomain(snapshot);
        VideoSnapshotPO existing = mapper.selectById(snapshot.getVid());
        if (existing == null) {
            try {
                return mapper.insertSnapshot(po) > 0;
            } catch (DuplicateKeyException e) {
                // 并发插入冲突（已被其他并发线程抢先插入），转入数据库级原子条件更新
                log.debug("视频元数据快照并发插入冲突，转为原子条件更新: vid={}", snapshot.getVid());
                return mapper.updateIfNewerOrSameVersion(po) > 0;
            }
        }

        // 步骤 1：内存前置快速失败，过滤显然过期的低版本事件，减少数据库负载
        int existingVersion = existing.getMetadataVersion() != null ? existing.getMetadataVersion() : 0;
        if (snapshot.getMetadataVersion() < existingVersion) {
            log.debug("丢弃低版本视频元数据快照: vid={}, incomingVersion={}, existingVersion={}",
                    snapshot.getVid(), snapshot.getMetadataVersion(), existingVersion);
            return false;
        }

        // 步骤 2：行锁保护下的原子条件更新，杜绝并发 Check-Then-Act 造成的版本降级
        int affected = mapper.updateIfNewerOrSameVersion(po);
        if (affected == 0) {
            log.debug("视频元数据快照原子更新被更高版本拒绝: vid={}, incomingVersion={}",
                    snapshot.getVid(), snapshot.getMetadataVersion());
            return false;
        }
        return true;
    }
}
