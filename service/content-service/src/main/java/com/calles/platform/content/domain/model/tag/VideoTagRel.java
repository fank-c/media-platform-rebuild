package com.calles.platform.content.domain.model.tag;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频与标签关联多对多领域实体 (VideoTagRel)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：内容服务内部的轻量从属关联实体；</li>
 *   <li><b>物理映射</b>：对应数据库中间表 {@code video_tag_rel}，维护视频内部 ID 与全局标签 ID 的多对多映射；</li>
 *   <li><b>生命周期</b>：依附于视频聚合根，当视频打标变更或视频删除时级联更新。</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoTagRel {

    /** 关联记录全局唯一主键 ID (UUID 32位无短横线)。 */
    private String id;

    /** 关联的视频内部全局主键 ID (关联 video_content.id)。 */
    private String videoId;

    /** 关联的标签全局字典主键 ID (关联 content_tag.id)。 */
    private String tagId;

    /** 关联关系绑定创建时间。 */
    private LocalDateTime createdAt;

    /**
     * 工厂方法：构建视频与标签的绑定关联实体。
     *
     * @param id 预生成的 UUID 主键（可为空，持久化前自动生成）
     * @param videoId 视频内部 ID
     * @param tagId 标签全局主键 ID
     * @return 初始化的关联实体
     * @throws IllegalArgumentException 当 videoId 或 tagId 为空时抛出
     */
    public static VideoTagRel create(String id, String videoId, String tagId) {
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("videoId 不能为空");
        }
        if (tagId == null || tagId.isBlank()) {
            throw new IllegalArgumentException("tagId 不能为空");
        }
        return VideoTagRel.builder()
                .id(id)
                .videoId(videoId)
                .tagId(tagId)
                .build();
    }
}
