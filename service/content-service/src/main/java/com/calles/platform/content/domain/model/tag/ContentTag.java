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
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：全站统一的标签轻量词典实体；</li>
 *   <li><b>协作对象</b>：与视频内容通过 {@link VideoTagRel} 多对多逻辑关联；</li>
 *   <li><b>热度机制</b>：维护关联视频的引用热度计数，支持全站热门标签的高效检索；</li>
 *   <li><b>治理能力</b>：支持平台管理人员对涉违规标签进行下线屏蔽 (DISABLED) 与恢复启用 (ACTIVE)。</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentTag {

    /** 标签全局唯一标识 (UUID 32位无短横线)。 */
    private String id;

    /** 标签名称 (全局唯一，如 "Java", "SpringCloud")。 */
    private String name;

    /** 引用热度计数（当前关联的有效视频总数，保底非负）。 */
    private long referenceCount;

    /** 标签治理可用状态 (ACTIVE=启用, DISABLED=下线屏蔽)。 */
    private CommonStatus status;

    /** 标签词条初次创建时间。 */
    private LocalDateTime createdAt;

    /** 标签信息最后修改或热度变更时间。 */
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：初始化创建全新标签。
     *
     * @param id 预生成的 UUID 主键标识
     * @param name 标签名称文本（会自动 trim）
     * @return 初始热度为 0 且状态为 ACTIVE 的新标签实体
     * @throws IllegalArgumentException 当 id 或 name 为空时抛出
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
     * 增加该标签的引用热度计数 (+1)。
     */
    public void incrementReference() {
        this.referenceCount++;
    }

    /**
     * 减少该标签的引用热度计数 (-1)，带保底非负约束。
     */
    public void decrementReference() {
        if (this.referenceCount > 0) {
            this.referenceCount--;
        }
    }

    /**
     * 平台治理下线或屏蔽该标签。
     */
    public void disable() {
        this.status = CommonStatus.DISABLED;
    }

    /**
     * 恢复启用该标签。
     */
    public void enable() {
        this.status = CommonStatus.ACTIVE;
    }

    /**
     * 检查标签当前是否处于正常启用可用状态。
     *
     * @return true 表示标签处于 ACTIVE 状态
     */
    public boolean isActive() {
        return this.status == CommonStatus.ACTIVE;
    }
}
