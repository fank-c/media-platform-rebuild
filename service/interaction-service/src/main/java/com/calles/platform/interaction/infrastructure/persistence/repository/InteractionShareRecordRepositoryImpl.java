package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.interaction.domain.model.share.InteractionShareRecord;
import com.calles.platform.interaction.domain.repository.InteractionShareRecordRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.InteractionShareRecordPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.InteractionShareRecordMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频分享防重记录仓储实现类。
 */
@Repository
@RequiredArgsConstructor
public class InteractionShareRecordRepositoryImpl implements InteractionShareRecordRepository {

    private final InteractionShareRecordMapper mapper;

    @Override
    public Optional<InteractionShareRecord> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<InteractionShareRecordPO> wrapper = new LambdaQueryWrapper<InteractionShareRecordPO>()
                .eq(InteractionShareRecordPO::getIdempotencyKey, idempotencyKey.trim());
        InteractionShareRecordPO po = mapper.selectOne(wrapper);
        return Optional.ofNullable(po).map(InteractionShareRecordPO::toDomain);
    }

    @Override
    public void save(InteractionShareRecord record) {
        if (record == null) {
            return;
        }
        mapper.insert(InteractionShareRecordPO.fromDomain(record));
    }
}
