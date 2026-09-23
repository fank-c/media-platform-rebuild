package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.share.InteractionShareRecord;
import java.util.Optional;

/**
 * 视频分享防重记录仓储接口。
 */
public interface InteractionShareRecordRepository {

    /**
     * 根据客户端幂等键查询分享记录。
     *
     * @param idempotencyKey 客户端幂等键
     * @return 分享记录实体 (若不存在返回 empty)
     */
    Optional<InteractionShareRecord> findByIdempotencyKey(String idempotencyKey);

    /**
     * 保存分享防重记录。
     *
     * @param record 分享记录实体
     */
    void save(InteractionShareRecord record);
}
