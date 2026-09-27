package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.calles.platform.interaction.domain.model.watch.WatchSession;
import com.calles.platform.interaction.domain.repository.WatchSessionRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchSessionPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.WatchSessionMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 观看会话仓储实现类。
 */
@Repository
@RequiredArgsConstructor
public class WatchSessionRepositoryImpl implements WatchSessionRepository {

    private final WatchSessionMapper mapper;

    @Override
    public Optional<WatchSession> findById(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectById(sessionId)).map(WatchSessionPO::toDomain);
    }

    @Override
    public Optional<WatchSession> findByStartRequestKey(String userId, String vid, String startRequestKey) {
        if (userId == null || userId.isBlank() || vid == null || vid.isBlank()
                || startRequestKey == null || startRequestKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectByStartRequestKey(userId, vid, startRequestKey))
                .map(WatchSessionPO::toDomain);
    }

    @Override
    public void insert(WatchSession session) {
        if (session == null) {
            return;
        }
        mapper.insertSession(WatchSessionPO.fromDomain(session));
    }

    @Override
    public void updateHeartbeat(WatchSession session) {
        if (session == null) {
            return;
        }
        mapper.updateHeartbeat(WatchSessionPO.fromDomain(session));
    }

    @Override
    public int close(String sessionId, LocalDateTime now) {
        if (sessionId == null || sessionId.isBlank() || now == null) {
            return 0;
        }
        return mapper.closeSession(sessionId, now);
    }

    @Override
    public int deleteStaleBefore(LocalDateTime threshold, int limit) {
        if (threshold == null || limit <= 0) {
            return 0;
        }
        return mapper.deleteStaleBefore(threshold, limit);
    }
}
