package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.share.InteractionShareRecord;
import java.util.Optional;

/**
 * 视频分享防重记录仓储接口。
 */
public interface InteractionShareRecordRepository {

    /**
     * 根据用户 ID 与客户端幂等键查询分享记录。
     *
     * @param userId 操作用户 ID
     * @param idempotencyKey 客户端幂等键
     * @return 分享记录实体 (若不存在返回 empty)
     */
    Optional<InteractionShareRecord> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    /**
     * 保存分享防重记录。
     *
     * @param record 分享记录实体
     */
    void save(InteractionShareRecord record);

    /**
     * 采用当前读 (FOR UPDATE) 根据用户 ID 与客户端幂等键查询分享记录。
     *
     * <p>用于并发插入冲突时穿透 MVCC 快照，实时获取胜出事务已提交的记录。</p>
     *
     * @param userId 操作用户 ID
     * @param idempotencyKey 客户端幂等键
     * @return 分享记录实体 (若不存在返回 empty)
     */
    Optional<InteractionShareRecord> findByUserIdAndIdempotencyKeyForUpdate(String userId, String idempotencyKey);
}
