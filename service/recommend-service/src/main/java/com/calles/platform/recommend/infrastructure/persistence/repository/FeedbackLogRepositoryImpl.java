package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.domain.model.feedback.FeedbackLog;
import com.calles.platform.recommend.domain.repository.FeedbackLogRepository;
import com.calles.platform.recommend.infrastructure.persistence.entity.FeedbackLogPO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.FeedbackLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 行为反馈流水仓储实现类 (FeedbackLogRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>防腐隔离</b>：负责 {@link FeedbackLog} 领域实体与 {@link FeedbackLogPO} 数据库实体间的互转；</li>
 *   <li><b>不可篡改写入</b>：严格仅执行追加操作，不提供更新与业务物理删除能力；</li>
 *   <li><b>时序分页检索</b>：支持按发生时间倒序拉取用户近期真实行为快照。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class FeedbackLogRepositoryImpl implements FeedbackLogRepository {

    private final FeedbackLogMapper mapper;

    @Override
    public void save(FeedbackLog logEntity) {
        if (logEntity == null) {
            return;
        }
        FeedbackLogPO po = toPO(logEntity);
        mapper.insert(po);
        log.debug("单笔追加行为反馈流水: id={}, userId={}, vid={}, action={}",
                logEntity.getId(), logEntity.getUserId(), logEntity.getVid(), logEntity.getActionType());
    }

    @Override
    public void saveBatch(List<FeedbackLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return;
        }
        for (FeedbackLog logEntity : logs) {
            if (logEntity != null) {
                mapper.insert(toPO(logEntity));
            }
        }
        log.debug("批量写入行为反馈流水成功: count={}", logs.size());
    }

    @Override
    public List<FeedbackLog> findRecentByUserId(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        int validLimit = limit > 0 ? Math.min(limit, 200) : 50;
        LambdaQueryWrapper<FeedbackLogPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FeedbackLogPO::getUserId, userId)
                .orderByDesc(FeedbackLogPO::getOccurredAt)
                .last("LIMIT " + validLimit);
        List<FeedbackLogPO> pos = mapper.selectList(wrapper);
        return pos.stream().map(this::toDomain).collect(Collectors.toList());
    }

    private FeedbackLog toDomain(FeedbackLogPO po) {
        if (po == null) {
            return null;
        }
        return new FeedbackLog(
                po.getId(),
                po.getUserId(),
                po.getVid(),
                FeedbackActionType.fromCode(po.getActionType()),
                po.getPlayDuration() != null ? po.getPlayDuration() : 0,
                po.getVideoDuration() != null ? po.getVideoDuration() : 0,
                po.getDomainTagIds(),
                po.getTopicTagIds(),
                po.getAuthorId(),
                po.getTraceId(),
                po.getOccurredAt(),
                po.getCreatedAt()
        );
    }

    private FeedbackLogPO toPO(FeedbackLog domain) {
        FeedbackLogPO po = new FeedbackLogPO();
        po.setId(domain.getId());
        po.setUserId(domain.getUserId());
        po.setVid(domain.getVid());
        po.setActionType(domain.getActionType().getCode());
        po.setPlayDuration(domain.getPlayDuration());
        po.setVideoDuration(domain.getVideoDuration());
        po.setDomainTagIds(domain.getDomainTagIds());
        po.setTopicTagIds(domain.getTopicTagIds());
        po.setAuthorId(domain.getAuthorId());
        po.setTraceId(domain.getTraceId());
        po.setOccurredAt(domain.getOccurredAt());
        po.setCreatedAt(domain.getCreatedAt());
        return po;
    }
}
