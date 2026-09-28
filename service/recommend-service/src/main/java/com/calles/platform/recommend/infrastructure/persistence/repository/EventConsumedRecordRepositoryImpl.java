package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.recommend.domain.model.event.EventConsumedRecord;
import com.calles.platform.recommend.domain.repository.EventConsumedRecordRepository;
import com.calles.platform.recommend.infrastructure.persistence.entity.EventConsumedRecordPO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.EventConsumedRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 互动事件消费幂等仓储 MyBatis-Plus 实现类 (EventConsumedRecordRepositoryImpl)。
 *
 * <p>核心职责：
 * <ul>
 *   <li>依托数据库主键唯一约束与 {@link DuplicateKeyException} 异常捕获，实现无锁原子防重保存；</li>
 *   <li>负责领域实体 {@link EventConsumedRecord} 与持久化对象 {@link EventConsumedRecordPO} 的相互转换；</li>
 *   <li>支持按消费时间清理过期记录，控制表容量。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class EventConsumedRecordRepositoryImpl implements EventConsumedRecordRepository {

    private final EventConsumedRecordMapper mapper;

    @Override
    public boolean saveIfAbsent(EventConsumedRecord record) {
        if (record == null) {
            return false;
        }

        EventConsumedRecordPO po = toPO(record);
        try {
            int rows = mapper.insert(po);
            return rows > 0;
        } catch (DuplicateKeyException e) {
            // 主键 uk/pk 冲突，说明该事件已被其他实例或前序任务处理完成
            log.debug("互动事件已被消费，主键冲突拦截: eventId={}", record.getEventId());
            return false;
        }
    }

    @Override
    public Optional<EventConsumedRecord> findByEventId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        EventConsumedRecordPO po = mapper.selectById(eventId);
        return Optional.ofNullable(po).map(this::toDomain);
    }

    @Override
    public int deleteBeforeConsumedAt(LocalDateTime before) {
        if (before == null) {
            return 0;
        }
        LambdaQueryWrapper<EventConsumedRecordPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(EventConsumedRecordPO::getConsumedAt, before);
        return mapper.delete(wrapper);
    }

    private EventConsumedRecord toDomain(EventConsumedRecordPO po) {
        return new EventConsumedRecord(
                po.getEventId(),
                po.getEventType(),
                po.getUserId(),
                po.getVid(),
                po.getAuthorId(),
                po.getAction(),
                po.getState(),
                po.getConsumedAt()
        );
    }

    private EventConsumedRecordPO toPO(EventConsumedRecord domain) {
        EventConsumedRecordPO po = new EventConsumedRecordPO();
        po.setEventId(domain.getEventId());
        po.setEventType(domain.getEventType());
        po.setUserId(domain.getUserId());
        po.setVid(domain.getVid());
        po.setAuthorId(domain.getAuthorId());
        po.setAction(domain.getAction());
        po.setState(domain.getState());
        po.setConsumedAt(domain.getConsumedAt());
        return po;
    }
}
