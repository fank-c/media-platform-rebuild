package com.calles.platform.interaction.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频互动统计持久化对象 (PO)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interaction_video_counter")
public class VideoCounterPO {

    /** 视频公开业务短码 (主键)。 */
    @TableId("vid")
    private String vid;

    /** 累计播放量。 */
    @TableField("view_count")
    private Long viewCount;

    /** 累计点赞数。 */
    @TableField("like_count")
    private Long likeCount;

    /** 累计收藏数。 */
    @TableField("star_count")
    private Long starCount;

    /** 累计分享数。 */
    @TableField("share_count")
    private Long shareCount;

    /** 累计评论数。 */
    @TableField("comment_count")
    private Long commentCount;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public VideoCounter toDomain() {
        return VideoCounter.builder()
                .vid(this.vid)
                .viewCount(this.viewCount != null ? this.viewCount : 0L)
                .likeCount(this.likeCount != null ? this.likeCount : 0L)
                .starCount(this.starCount != null ? this.starCount : 0L)
                .shareCount(this.shareCount != null ? this.shareCount : 0L)
                .commentCount(this.commentCount != null ? this.commentCount : 0L)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    public static VideoCounterPO fromDomain(VideoCounter domain) {
        if (domain == null) {
            return null;
        }
        return VideoCounterPO.builder()
                .vid(domain.getVid())
                .viewCount(domain.getViewCount())
                .likeCount(domain.getLikeCount())
                .starCount(domain.getStarCount())
                .shareCount(domain.getShareCount())
                .commentCount(domain.getCommentCount())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
