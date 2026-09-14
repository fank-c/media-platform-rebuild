package com.calles.platform.content.domain.model.tag;

import com.calles.platform.content.domain.model.CommonStatus;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 标签全局领域实体 (ContentTag)。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentTag {

    /** 标签主键 ID (UUID)。 */
    private String id;

    /** 标签唯一名称。 */
    private String name;

    /** 引用热度计数（关联的视频总数）。 */
    private long referenceCount;

    /** 标签可用状态 (ACTIVE=启用, DISABLED=下线屏蔽)。 */
    private CommonStatus status;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：创建新标签。
     */
    public static ContentTag create(String id, String name) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("标签ID不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("标签名称不能为空");
        }
        return ContentTag.builder()
                .id(id)
                .name(name.trim())
                .referenceCount(0L)
                .status(CommonStatus.ACTIVE)
                .build();
    }

    /**
     * 增加引用热度。
     */
    public void incrementReference() {
        this.referenceCount++;
    }

    /**
     * 减少引用热度。
     */
    public void decrementReference() {
        if (this.referenceCount > 0) {
            this.referenceCount--;
        }
    }

    /**
     * 下线或屏蔽标签。
     */
    public void disable() {
        this.status = CommonStatus.DISABLED;
    }

    /**
     * 启用标签。
     */
    public void enable() {
        this.status = CommonStatus.ACTIVE;
    }

    /**
     * 检查标签当前是否正常启用。
     */
    public boolean isActive() {
        return this.status == CommonStatus.ACTIVE;
    }
}
