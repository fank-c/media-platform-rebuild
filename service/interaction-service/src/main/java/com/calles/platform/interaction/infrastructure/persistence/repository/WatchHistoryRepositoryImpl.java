package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.WatchHistoryRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchHistoryPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.WatchHistoryMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 观看历史与断点仓储实现类。
 */
@Repository
@RequiredArgsConstructor
public class WatchHistoryRepositoryImpl implements WatchHistoryRepository {

    private final WatchHistoryMapper mapper;

    @Override
    public Optional<WatchHistory> findByUserAndVid(String userId, String vid) {
        if (userId == null || vid == null) {
            return Optional.empty();
        }
        LambdaQueryWrapper<WatchHistoryPO> wrapper = new LambdaQueryWrapper<WatchHistoryPO>()
                .eq(WatchHistoryPO::getUserId, userId)
                .eq(WatchHistoryPO::getVid, vid);
        WatchHistoryPO po = mapper.selectOne(wrapper);
        return Optional.ofNullable(po).map(WatchHistoryPO::toDomain);
    }

    @Override
    public Optional<WatchHistory> findPhysicalByUserAndVid(String userId, String vid) {
        if (userId == null || vid == null) {
            return Optional.empty();
        }
        WatchHistoryPO po = mapper.selectPhysicalByUserAndVid(userId, vid);
        return Optional.ofNullable(po).map(WatchHistoryPO::toDomain);
    }

    @Override
    public List<WatchHistory> findByUserId(String userId, int offset, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<WatchHistoryPO> wrapper = new LambdaQueryWrapper<WatchHistoryPO>()
                .eq(WatchHistoryPO::getUserId, userId)
                .orderByDesc(WatchHistoryPO::getLastWatchAt)
                .last("LIMIT " + Math.max(0, offset) + ", " + Math.max(1, limit));
        List<WatchHistoryPO> pos = mapper.selectList(wrapper);
        if (pos == null) {
            return List.of();
        }
        return pos.stream().map(WatchHistoryPO::toDomain).toList();
    }

    @Override
    public long countByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        LambdaQueryWrapper<WatchHistoryPO> wrapper = new LambdaQueryWrapper<WatchHistoryPO>()
                .eq(WatchHistoryPO::getUserId, userId);
        return mapper.selectCount(wrapper);
    }

    @Override
    public void save(WatchHistory history) {
        if (history == null) {
            return;
        }
        mapper.insert(WatchHistoryPO.fromDomain(history));
    }

    @Override
    public void update(WatchHistory history) {
        if (history == null) {
            return;
        }
        mapper.updateHeartbeat(WatchHistoryPO.fromDomain(history));
    }

    @Override
    public void revive(WatchHistory history) {
        if (history == null) {
            return;
        }
        mapper.reviveAndHeartbeat(WatchHistoryPO.fromDomain(history));
    }

    @Override
    public int deleteByUserAndVid(String userId, String vid) {
        if (userId == null || vid == null) {
            return 0;
        }
        LambdaQueryWrapper<WatchHistoryPO> wrapper = new LambdaQueryWrapper<WatchHistoryPO>()
                .eq(WatchHistoryPO::getUserId, userId)
                .eq(WatchHistoryPO::getVid, vid);
        return mapper.delete(wrapper);
    }

    @Override
    public int deleteAllByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        LambdaQueryWrapper<WatchHistoryPO> wrapper = new LambdaQueryWrapper<WatchHistoryPO>()
                .eq(WatchHistoryPO::getUserId, userId);
        return mapper.delete(wrapper);
    }

    @Override
    public int markCompletedIfUncompleted(String id) {
        if (id == null || id.isBlank()) {
            return 0;
        }
        return mapper.markCompletedIfUncompleted(id);
    }

    @Override
    public int claimValidPlay(String id, LocalDateTime now, LocalDateTime cooldownBoundary) {
        if (id == null || id.isBlank() || now == null || cooldownBoundary == null) {
            return 0;
        }
        return mapper.claimValidPlay(id, now, cooldownBoundary);
    }
}
