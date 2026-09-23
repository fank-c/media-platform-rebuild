package com.calles.platform.interaction.domain.model.like;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 用户视频点赞记录领域实体。
 *
 * <p>维护特定用户对特定视频的点赞事实，通过 status 支持点赞与取消点赞的高效反转。</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VideoLike {

    /** 记录全局唯一主键 ID (UUID)。 */
    private String id;

    /** 目标视频业务公开短码。 */
    private String vid;

    /** 点赞操作人用户账号 ID。 */
    private String userId;

    /** 点赞状态 (1=ACTIVE, 0=CANCELLED)。 */
    private LikeStatus status;

    /** 是否已逻辑删除：true=已删除, false=正常有效。 */
    private boolean deleted;

    /** 首次点赞时间。 */
    private LocalDateTime createdAt;

    /** 最近一次状态更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：新建点赞实体 (首次点赞)。
     *
     * @param vid 视频公开短码
     * @param userId 点赞用户 ID
     * @return 激活状态的点赞实体
     */
    public static VideoLike create(String vid, String userId) {
        if (vid == null || vid.isBlank()) {
            throw new IllegalArgumentException("视频业务短码不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户账号ID不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        return VideoLike.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .vid(vid.trim())
                .userId(userId.trim())
                .status(LikeStatus.ACTIVE)
                .deleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 逻辑删除点赞事实。
     */
    public void markDeleted() {
        this.deleted = true;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 取消点赞操作。
     *
     * @return true 若状态发生实质变更（原先为 ACTIVE）；false 若已处于取消状态（幂等）
     */
    public boolean cancel() {
        if (this.status == LikeStatus.CANCELLED) {
            return false;
        }
        this.status = LikeStatus.CANCELLED;
        this.updatedAt = LocalDateTime.now();
        return true;
    }

    /**
     * 重新激活点赞（此前曾取消点赞）。
     *
     * @return true 若状态发生实质变更（原先为 CANCELLED）；false 若已处于点赞状态（幂等）
     */
    public boolean reactivate() {
        if (this.status == LikeStatus.ACTIVE) {
            return false;
        }
        this.status = LikeStatus.ACTIVE;
        this.deleted = false;
        this.updatedAt = LocalDateTime.now();
        return true;
    }

    /**
     * 判断当前是否处于有效点赞状态。
     *
     * @return true 若当前状态为 ACTIVE
     */
    public boolean isActive() {
        return this.status == LikeStatus.ACTIVE && !this.deleted;
    }
}
