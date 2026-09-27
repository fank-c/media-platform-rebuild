package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.calles.platform.interaction.domain.model.watch.WatchEventClaim;
import com.calles.platform.interaction.domain.model.watch.WatchEventType;
import com.calles.platform.interaction.domain.repository.WatchEventClaimRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchEventClaimPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.WatchEventClaimMapper;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 观看事件凭据仓储实现类。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class WatchEventClaimRepositoryImpl implements WatchEventClaimRepository {

    private final WatchEventClaimMapper mapper;

    @Override
    public boolean tryClaim(WatchEventClaim claim) {
        if (claim == null) {
            return false;
        }
        try {
            return mapper.insertClaim(WatchEventClaimPO.fromDomain(claim)) > 0;
        } catch (DuplicateKeyException e) {
            // 唯一键冲突即"本会话该事件已被抢占"，属于正常并发路径而非错误
            log.debug("观看事件凭据已被抢占，跳过本次副作用: userId={}, vid={}, sessionId={}, eventType={}",
                    claim.getUserId(), claim.getVid(), claim.getSessionId(), claim.getEventType());
            return false;
        }
    }

    @Override
    public Set<WatchEventType> findClaimedTypes(String userId, String vid, String sessionId) {
        if (userId == null || userId.isBlank() || vid == null || vid.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Set.of();
        }
        List<String> rawTypes = mapper.selectClaimedEventTypes(userId, vid, sessionId);
        if (rawTypes == null || rawTypes.isEmpty()) {
            return Set.of();
        }
        Set<WatchEventType> types = new LinkedHashSet<>();
        for (String raw : rawTypes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                types.add(WatchEventType.valueOf(raw));
            } catch (IllegalArgumentException e) {
                // 未知凭据类型不影响心跳主流程，只记录以便排查
                log.warn("忽略未知观看事件凭据类型: userId={}, vid={}, sessionId={}, eventType={}",
                        userId, vid, sessionId, raw);
            }
        }
        return types;
    }

    @Override
    public Set<String> findCompletedVids(String userId, Collection<String> vids) {
        if (userId == null || userId.isBlank() || vids == null || vids.isEmpty()) {
            return Set.of();
        }
        List<String> matched = mapper.selectClaimedVids(userId, WatchEventType.WATCH_COMPLETED.name(), vids);
        if (matched == null || matched.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(matched);
    }

    @Override
    public void attachOutboxEventId(String id, String outboxEventId) {
        if (id == null || id.isBlank() || outboxEventId == null || outboxEventId.isBlank()) {
            return;
        }
        mapper.attachOutboxEventId(id, outboxEventId);
    }

    @Override
    public int deleteBefore(LocalDateTime threshold, int limit) {
        if (threshold == null || limit <= 0) {
            return 0;
        }
        return mapper.deleteBefore(threshold, limit);
    }
}
