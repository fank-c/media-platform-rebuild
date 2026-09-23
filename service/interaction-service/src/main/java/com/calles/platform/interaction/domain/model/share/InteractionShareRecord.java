package com.calles.platform.interaction.domain.model.share;

import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

/**
 * 视频分享幂等防重记录领域模型。
 *
 * <p>维护客户端请求幂等键与分享事实的对应关系，防止重试导致计数与事件重复。</p>
 */
@Getter
public class InteractionShareRecord {

    /** 记录全局唯一标识 (UUID)。 */
    private final String id;

    /** 客户端提供的请求幂等键。 */
    private final String idempotencyKey;

    /** 操作用户账号 ID。 */
    private final String userId;

    /** 视频公开业务短码。 */
    private final String vid;

    /** 记录创建时间。 */
    private final Instant createdAt;

    /** 是否已逻辑删除：true=已删除, false=正常有效。 */
    private final boolean deleted;

    public InteractionShareRecord(String id, String idempotencyKey, String userId, String vid, Instant createdAt) {
        this(id, idempotencyKey, userId, vid, createdAt, false);
    }

    public InteractionShareRecord(String id, String idempotencyKey, String userId, String vid, Instant createdAt, boolean deleted) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.vid = vid;
        this.createdAt = createdAt;
        this.deleted = deleted;
    }

    /**
     * 工厂方法：基于客户端幂等键创建新的分享记录实体。
     *
     * @param idempotencyKey 幂等键
     * @param userId 用户 ID
     * @param vid 视频短码
     * @return 新建实体
     */
    public static InteractionShareRecord create(String idempotencyKey, String userId, String vid) {
        String id = UUID.randomUUID().toString().replace("-", "");
        return new InteractionShareRecord(id, idempotencyKey, userId, vid, Instant.now(), false);
    }
}
