package com.calles.platform.content.domain.model.tag;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频与标签关联多对多实体 (VideoTagRel)。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoTagRel {

    /** 关联记录主键 ID (UUID)。 */
    private String id;

    /** 视频内部 ID (关联 video_content.id)。 */
    private String videoId;

    /** 标签 ID (关联 content_tag.id)。 */
    private String tagId;

    /** 关联创建时间。 */
    private LocalDateTime createdAt;

    /**
     * 工厂方法：创建关联关系。
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
