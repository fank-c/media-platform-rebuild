package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.interaction.domain.model.watch.WatchHistoryEntry;
import com.calles.platform.interaction.domain.model.watch.WatchProgress;
import com.calles.platform.interaction.domain.repository.WatchEventClaimRepository;
import com.calles.platform.interaction.domain.repository.WatchProgressRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchProgressHistoryRow;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchProgressPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.WatchProgressMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 观看进度仓储实现类。
 */
@Repository
@RequiredArgsConstructor
public class WatchProgressRepositoryImpl implements WatchProgressRepository {

    private final WatchProgressMapper mapper;

    /** 完播凭据仓储，用于在历史列表上标注完播状态。 */
    private final WatchEventClaimRepository claimRepository;

    @Override
    public Optional<WatchProgress> lockByUserAndVid(String userId, String vid) {
        if (isBlank(userId) || isBlank(vid)) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectForUpdate(userId, vid)).map(WatchProgressPO::toDomain);
    }

    @Override
    public Optional<WatchProgress> findByUserAndVid(String userId, String vid) {
        if (isBlank(userId) || isBlank(vid)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<WatchProgressPO> wrapper = new LambdaQueryWrapper<WatchProgressPO>()
                .eq(WatchProgressPO::getUserId, userId)
                .eq(WatchProgressPO::getVid, vid);
        return Optional.ofNullable(mapper.selectOne(wrapper)).map(WatchProgressPO::toDomain);
    }

    @Override
    public void insert(WatchProgress progress) {
        if (progress == null) {
            return;
        }
        mapper.insertProgress(WatchProgressPO.fromDomain(progress));
    }

    @Override
    public void updateHeartbeat(WatchProgress progress) {
        if (progress == null) {
            return;
        }
        mapper.updateHeartbeat(WatchProgressPO.fromDomain(progress));
    }

    @Override
    public void markViewClaimed(String id, LocalDateTime now) {
        if (isBlank(id) || now == null) {
            return;
        }
        mapper.markViewClaimed(id, now);
    }

    @Override
    public List<WatchHistoryEntry> findVisibleHistoryPage(String userId, int offset, int limit) {
        if (isBlank(userId)) {
            return List.of();
        }
        List<WatchProgressHistoryRow> rows = mapper.selectVisibleHistoryPage(
                userId, Math.max(0, offset), Math.max(1, limit));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        // 完播状态不属于进度表字段，按本页视频集合一次性查询完播凭据后统一标注
        Set<String> completedVids = claimRepository.findCompletedVids(
                userId, rows.stream().map(WatchProgressHistoryRow::getVid).toList());
        return rows.stream().map(row -> row.toEntry(completedVids)).toList();
    }

    @Override
    public int hideByUserAndVid(String userId, String vid) {
        if (isBlank(userId) || isBlank(vid)) {
            return 0;
        }
        return mapper.hideByUserAndVid(userId, vid);
    }

    @Override
    public int hideAllByUserId(String userId) {
        if (isBlank(userId)) {
            return 0;
        }
        return mapper.hideAllByUserId(userId);
    }

    @Override
    public int detachStaleActiveSessions(LocalDateTime threshold, int limit) {
        if (threshold == null || limit <= 0) {
            return 0;
        }
        return mapper.detachStaleActiveSessions(threshold, limit);
    }

    @Override
    public int deleteHiddenBefore(LocalDateTime threshold, int limit) {
        if (threshold == null || limit <= 0) {
            return 0;
        }
        return mapper.deleteHiddenBefore(threshold, limit);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
