package com.calles.platform.content.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.content.domain.model.CommonStatus;
import com.calles.platform.content.domain.model.video.ContentVisibility;
import com.calles.platform.content.domain.model.video.PublishStatus;
import com.calles.platform.content.domain.model.video.VideoContent;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频内容数据库持久化实体 (PO)。
 *
 * <p>映射底层数据库表 {@code video_content}，承载视频元数据、双状态（治理/发布）、互动快照计数与乐观锁并发控制。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("video_content")
public class VideoContentPO {

    /** 视频内部主键 ID (UUID 32位无短横线)。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 业务公开唯一标识 (如 cv2026090001)。 */
    @TableField("vid")
    private String vid;

    /** 创作者账号 ID (关联 auth_account.id)。 */
    @TableField("author_id")
    private String authorId;

    /** 视频标题。 */
    @TableField("title")
    private String title;

    /** 视频文本简介。 */
    @TableField("description")
    private String description;

    /** 主视频源文件资产 ID (关联 file_asset.id)。 */
    @TableField("video_file_id")
    private String videoFileId;

    /** 封面图片文件资产 ID (关联 file_asset.id)。 */
    @TableField("cover_file_id")
    private String coverFileId;

    /** 视频总时长 (秒)。 */
    @TableField("duration")
    private Integer duration;

    /** 逗号分隔的轻量关键词标签快照 (供 Embedding 消费)。 */
    @TableField("tags")
    private String tags;

    /** 平台级治理基准状态 (ACTIVE / DISABLED)。 */
    @TableField("status")
    private String status;

    /** 业务发布流转生命周期 (DRAFT, AUDITING, PUBLISHED, REJECTED, OFFLINE)。 */
    @TableField("publish_status")
    private String publishStatus;

    /** 审核拒绝或封禁下架的具体原因说明。 */
    @TableField("reject_reason")
    private String rejectReason;

    /** 公开可见性范围 (PUBLIC, PRIVATE, UNLISTED)。 */
    @TableField("visibility")
    private String visibility;

    /** 播放量计数快照。 */
    @TableField("view_count")
    private Long viewCount;

    /** 点赞量计数快照。 */
    @TableField("like_count")
    private Long likeCount;

    /** 评论量计数快照。 */
    @TableField("comment_count")
    private Long commentCount;

    /** 收藏量计数快照。 */
    @TableField("star_count")
    private Long starCount;

    /** 分享量计数快照。 */
    @TableField("share_count")
    private Long shareCount;

    /** 正式公开/发布时间。 */
    @TableField("published_at")
    private LocalDateTime publishedAt;

    /** 逻辑删除标记 (0=正常, 1=已删除)。 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /** 乐观锁并发版本号。 */
    @TableField("revision")
    private Long revision;

    /** 记录创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 记录更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 将 PO 转为领域模型。
     */
    public VideoContent toDomain() {
        return VideoContent.builder()
                .id(this.id)
                .vid(this.vid)
                .authorId(this.authorId)
                .title(this.title)
                .description(this.description)
                .videoFileId(this.videoFileId)
                .coverFileId(this.coverFileId)
                .duration(this.duration != null ? this.duration : 0)
                .tags(this.tags)
                .status(this.status != null ? CommonStatus.valueOf(this.status) : CommonStatus.ACTIVE)
                .publishStatus(this.publishStatus != null ? PublishStatus.valueOf(this.publishStatus) : PublishStatus.DRAFT)
                .rejectReason(this.rejectReason)
                .visibility(this.visibility != null ? ContentVisibility.valueOf(this.visibility) : ContentVisibility.PUBLIC)
                .viewCount(this.viewCount != null ? this.viewCount : 0L)
                .likeCount(this.likeCount != null ? this.likeCount : 0L)
                .commentCount(this.commentCount != null ? this.commentCount : 0L)
                .starCount(this.starCount != null ? this.starCount : 0L)
                .shareCount(this.shareCount != null ? this.shareCount : 0L)
                .publishedAt(this.publishedAt)
                .deleted(this.deleted != null ? this.deleted : 0)
                .revision(this.revision != null ? this.revision : 0L)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    /**
     * 从领域模型构造 PO。
     */
    public static VideoContentPO fromDomain(VideoContent domain) {
        if (domain == null) {
            return null;
        }
        return VideoContentPO.builder()
                .id(domain.getId())
                .vid(domain.getVid())
                .authorId(domain.getAuthorId())
                .title(domain.getTitle())
                .description(domain.getDescription())
                .videoFileId(domain.getVideoFileId())
                .coverFileId(domain.getCoverFileId())
                .duration(domain.getDuration())
                .tags(domain.getTags())
                .status(domain.getStatus() != null ? domain.getStatus().getValue() : CommonStatus.ACTIVE.getValue())
                .publishStatus(domain.getPublishStatus() != null ? domain.getPublishStatus().getValue() : PublishStatus.DRAFT.getValue())
                .rejectReason(domain.getRejectReason())
                .visibility(domain.getVisibility() != null ? domain.getVisibility().getValue() : ContentVisibility.PUBLIC.getValue())
                .viewCount(domain.getViewCount())
                .likeCount(domain.getLikeCount())
                .commentCount(domain.getCommentCount())
                .starCount(domain.getStarCount())
                .shareCount(domain.getShareCount())
                .publishedAt(domain.getPublishedAt())
                .deleted(domain.getDeleted() != null ? domain.getDeleted() : 0)
                .revision(domain.getRevision() != null ? domain.getRevision() : 0L)
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
