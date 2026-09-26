package com.calles.platform.interaction.domain.model.star;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 用户收藏夹领域实体。
 *
 * <p>支持默认收藏夹（每个用户唯一）以及用户自主创建的自定义多收藏夹。</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class StarFolder {

    /** 默认收藏夹固定默认标题。 */
    public static final String DEFAULT_FOLDER_TITLE = "默认收藏夹";

    /** 收藏夹全局唯一标识 ID (UUID)。 */
    private String id;

    /** 所属用户账号 ID。 */
    private String userId;

    /** 收藏夹名称标题。 */
    private String title;

    /** 是否为系统默认收藏夹：true=默认收藏夹, false=自定义收藏夹。 */
    private boolean isDefault;

    /** 状态：1=正常可用, 0=已删除。 */
    private int status;

    /** 是否已逻辑删除：true=已删除, false=正常有效。 */
    private boolean deleted;

    /** 收藏夹创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：为用户创建默认收藏夹。
     *
     * @param userId 所属用户 ID
     * @return 初始化的默认收藏夹实体
     */
    public static StarFolder createDefault(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户账号ID不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        return StarFolder.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId.trim())
                .title(DEFAULT_FOLDER_TITLE)
                .isDefault(true)
                .status(1)
                .deleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 工厂方法：新建自定义收藏夹。
     *
     * @param userId 所属用户 ID
     * @param title 收藏夹名称
     * @return 初始化的自定义收藏夹实体
     */
    public static StarFolder createCustom(String userId, String title) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户账号ID不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("收藏夹标题不能为空");
        }
        String trimmedTitle = title.trim();
        if (trimmedTitle.length() > 64) {
            throw new IllegalArgumentException("收藏夹标题长度不能超过64字符");
        }
        LocalDateTime now = LocalDateTime.now();
        return StarFolder.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId.trim())
                .title(trimmedTitle)
                .isDefault(false)
                .status(1)
                .deleted(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 修改收藏夹标题（默认收藏夹不可更名）。
     *
     * @param newTitle 新标题名称
     */
    public void rename(String newTitle) {
        if (this.isDefault) {
            throw new IllegalStateException("默认收藏夹不可更名");
        }
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("新标题不能为空");
        }
        String trimmedTitle = newTitle.trim();
        if (trimmedTitle.length() > 64) {
            throw new IllegalArgumentException("收藏夹标题长度不能超过64字符");
        }
        this.title = trimmedTitle;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 逻辑删除收藏夹（默认收藏夹不可删除）。
     */
    public void delete() {
        if (this.isDefault) {
            throw new IllegalStateException("默认收藏夹不可删除");
        }
        this.status = 0;
        this.deleted = true;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 校验当前收藏夹是否有效未删除。
     *
     * @return true 若可用
     */
    public boolean isActive() {
        return this.status == 1 && !this.deleted;
    }
}
