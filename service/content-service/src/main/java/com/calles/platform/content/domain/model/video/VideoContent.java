package com.calles.platform.content.domain.model.video;

import com.calles.platform.content.domain.model.CommonStatus;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 视频内容领域聚合根 (VideoContent)。
 *
 * <p>核心业务职责与设计原则：
 * <ul>
 *   <li><b>双 ID 标识</b>：内部全局主键 {@code id} (UUID) 负责系统内各服务逻辑关联，业务编码 {@code vid} (如 cv...) 面向对外展示与路由；</li>
 *   <li><b>非物理外键解耦</b>：作者使用 {@code authorId} 逻辑关联认证账号，媒体资源使用 {@code videoFileId} 与 {@code coverFileId} 逻辑关联文件资产；</li>
 *   <li><b>轻量标签快照</b>：使用 {@code tags} 字符串存储逗号分隔的轻量关键词，直接供后续文本向量化 (Embedding) 计算消费；</li>
 *   <li><b>状态分层对齐</b>：{@code status} (CommonStatus.ACTIVE/DISABLED) 表征全平台统一的可用性/封禁状态；{@code publishStatus} (DRAFT/AUDITING/PUBLISHED/REJECTED/OFFLINE) 表达内容发布流转生命周期；</li>
 *   <li><b>快照计数</b>：冗余存储互动计数字段，由互动微服务异步批量回写刷新，提升前台只读性能；</li>
 *   <li><b>状态机约束</b>：内置状态跃迁校验方法，防止非法状态流转。</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoContent {

    /** 视频内部唯一标识 (UUID 字符串)。 */
    private String id;

    /** 业务公开唯一标识 (如 cv2026090001)。 */
    private String vid;

    /** 作者账号 ID (逻辑关联 auth_account.id)。 */
    private String authorId;

    /** 视频标题。 */
    private String title;

    /** 视频简介文本描述。 */
    private String description;

    /** 原始主视频文件 ID (引用 file_asset.id)。 */
    private String videoFileId;

    /** 封面图片文件 ID (引用 file_asset.id)。 */
    private String coverFileId;

    /** 视频时长 (秒)。 */
    private int duration;

    /** 轻量关键词标签 (英文逗号分隔，如 "Java,微服务,SpringCloud")。 */
    private String tags;

    /** 平台级可用状态 (ACTIVE=正常, DISABLED=违规封禁/冻结)。 */
    private CommonStatus status;

    /** 发布与审核流转生命周期。 */
    private PublishStatus publishStatus;

    /** 审核拒绝或封禁/下架原因。 */
    private String rejectReason;

    /** 公开可见性范围。 */
    private ContentVisibility visibility;

    /** 播放量快照。 */
    private long viewCount;

    /** 点赞数快照。 */
    private long likeCount;

    /** 评论数快照。 */
    private long commentCount;

    /** 收藏数快照。 */
    private long starCount;

    /** 分享数快照。 */
    private long shareCount;

    /** 正式公开/发布时间。 */
    private LocalDateTime publishedAt;

    /** 逻辑删除标记：0=未删除，1=已删除。 */
    private Integer deleted;

    /** 乐观锁并发版本号。 */
    private Long revision;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：新建视频草稿 (DRAFT)。
     */
    public static VideoContent createDraft(String id, String vid, String authorId, String title,
                                          String description, String videoFileId, String coverFileId,
                                          int duration, String tags) {
        validateBasicInfo(id, vid, authorId, title, videoFileId, coverFileId, duration);

        return VideoContent.builder()
                .id(id)
                .vid(vid)
                .authorId(authorId)
                .title(title.trim())
                .description(description != null ? description.trim() : "")
                .videoFileId(videoFileId)
                .coverFileId(coverFileId)
                .duration(duration)
                .tags(cleanTags(tags))
                .status(CommonStatus.ACTIVE)
                .publishStatus(PublishStatus.DRAFT)
                .visibility(ContentVisibility.PUBLIC)
                .viewCount(0L)
                .likeCount(0L)
                .commentCount(0L)
                .starCount(0L)
                .shareCount(0L)
                .deleted(0)
                .revision(0L)
                .build();
    }

    /**
     * 提交发布：从草稿或被拒绝状态变更为审核中 (AUDITING)。
     */
    public void submitForAudit() {
        if (this.status != CommonStatus.ACTIVE) {
            throw new IllegalStateException("被封禁/禁用的视频不可提交审核，当前状态: " + this.status);
        }
        if (this.publishStatus != PublishStatus.DRAFT && this.publishStatus != PublishStatus.REJECTED) {
            throw new IllegalStateException("只有草稿 (DRAFT) 或被拒绝 (REJECTED) 状态的视频可以提交审核，当前生命周期: " + this.publishStatus);
        }
        this.publishStatus = PublishStatus.AUDITING;
        this.rejectReason = null;
    }

    /**
     * 审核通过并正式发布 (PUBLISHED)。
     */
    public void publish(LocalDateTime publishTime) {
        if (this.publishStatus != PublishStatus.AUDITING) {
            throw new IllegalStateException("只有审核中 (AUDITING) 状态的视频可转为已发布，当前生命周期: " + this.publishStatus);
        }
        this.publishStatus = PublishStatus.PUBLISHED;
        this.rejectReason = null;
        if (this.publishedAt == null) {
            this.publishedAt = Objects.requireNonNullElseGet(publishTime, LocalDateTime::now);
        }
    }

    /**
     * 审核不通过拒绝 (REJECTED)。
     */
    public void reject(String reason) {
        if (this.publishStatus != PublishStatus.AUDITING) {
            throw new IllegalStateException("只有审核中 (AUDITING) 状态的视频可执行拒绝操作，当前生命周期: " + this.publishStatus);
        }
        this.publishStatus = PublishStatus.REJECTED;
        this.rejectReason = (reason != null && !reason.isBlank()) ? reason.trim() : "内容未通过平台审核规范";
    }

    /**
     * 创作者主动下架 (OFFLINE)。
     */
    public void takeOffline(String reason) {
        if (this.publishStatus != PublishStatus.PUBLISHED) {
            throw new IllegalStateException("只有已发布 (PUBLISHED) 状态的视频可以下架，当前生命周期: " + this.publishStatus);
        }
        this.publishStatus = PublishStatus.OFFLINE;
        this.rejectReason = (reason != null && !reason.isBlank()) ? reason.trim() : "视频已下架";
    }

    /**
     * 平台违规封禁/冻结 (DISABLED)。
     */
    public void ban(String reason) {
        this.status = CommonStatus.DISABLED;
        this.rejectReason = (reason != null && !reason.isBlank()) ? reason.trim() : "视频违规已被平台封禁";
    }

    /**
     * 平台解除封禁 (ACTIVE)。
     */
    public void unban() {
        this.status = CommonStatus.ACTIVE;
    }

    /**
     * 修改视频图文元信息。
     */
    public void updateMetadata(String newTitle, String newDescription, String newCoverFileId, String newTags) {
        if (newTitle != null && !newTitle.isBlank()) {
            this.title = newTitle.trim();
        }
        if (newDescription != null) {
            this.description = newDescription.trim();
        }
        if (newCoverFileId != null && !newCoverFileId.isBlank()) {
            this.coverFileId = newCoverFileId.trim();
        }
        if (newTags != null) {
            this.tags = cleanTags(newTags);
        }
    }

    /**
     * 更新互动快照计数（由 interaction 异步事件回写）。
     */
    public void updateMetricsSnapshot(long viewCount, long likeCount, long commentCount,
                                      long starCount, long shareCount) {
        this.viewCount = Math.max(0, viewCount);
        this.likeCount = Math.max(0, likeCount);
        this.commentCount = Math.max(0, commentCount);
        this.starCount = Math.max(0, starCount);
        this.shareCount = Math.max(0, shareCount);
    }

    private static void validateBasicInfo(String id, String vid, String authorId, String title,
                                          String videoFileId, String coverFileId, int duration) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("视频ID不能为空");
        }
        if (vid == null || vid.isBlank()) {
            throw new IllegalArgumentException("业务编码vid不能为空");
        }
        if (authorId == null || authorId.isBlank()) {
            throw new IllegalArgumentException("作者ID不能为空");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("视频标题不能为空");
        }
        if (videoFileId == null || videoFileId.isBlank()) {
            throw new IllegalArgumentException("视频文件ID不能为空");
        }
        if (coverFileId == null || coverFileId.isBlank()) {
            throw new IllegalArgumentException("封面图片文件ID不能为空");
        }
        if (duration < 0) {
            throw new IllegalArgumentException("视频时长不能为负数");
        }
    }

    private static String cleanTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return "";
        }
        // 去除多余空格并规范为英文逗号
        return rawTags.replace("，", ",").replaceAll("\\s+", "").trim();
    }
}
