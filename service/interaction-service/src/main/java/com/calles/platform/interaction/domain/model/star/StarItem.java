package com.calles.platform.interaction.domain.model.star;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 收藏夹内视频明细条目领域实体。
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StarItem {

    /** 明细主键 ID (UUID)。 */
    private String id;

    /** 所属收藏夹 ID。 */
    private String folderId;

    /** 业务视频公开短码。 */
    private String vid;

    /** 所属用户 ID (冗余支持高效反查本人是否收藏过)。 */
    private String userId;

    /** 收藏时间。 */
    private LocalDateTime createdAt;

    /** 是否已逻辑删除：true=已删除, false=正常有效。 */
    private boolean deleted;

    /**
     * 工厂方法：新建视频收藏明细条目。
     *
     * @param folderId 收藏夹 ID
     * @param vid 视频业务公开短码
     * @param userId 用户账号 ID
     * @return 收藏明细项
     */
    public static StarItem create(String folderId, String vid, String userId) {
        if (folderId == null || folderId.isBlank()) {
            throw new IllegalArgumentException("收藏夹ID不能为空");
        }
        if (vid == null || vid.isBlank()) {
            throw new IllegalArgumentException("视频业务短码不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户账号ID不能为空");
        }

        return StarItem.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .folderId(folderId.trim())
                .vid(vid.trim())
                .userId(userId.trim())
                .createdAt(LocalDateTime.now())
                .deleted(false)
                .build();
    }

    /**
     * 逻辑删除收藏条目。
     */
    public void markDeleted() {
        this.deleted = true;
    }

    /**
     * 自愈复活已逻辑删除的收藏条目。
     */
    public void revive() {
        this.deleted = false;
        this.createdAt = LocalDateTime.now();
    }
}
