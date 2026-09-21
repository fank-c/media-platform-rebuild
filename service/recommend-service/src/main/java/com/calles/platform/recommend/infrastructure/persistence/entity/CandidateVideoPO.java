package com.calles.platform.recommend.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 推荐候选池持久化数据对象 (PO)。
 *
 * <p>映射底层数据库表 {@code recommend_candidate_video}，负责推荐候选轻量元数据的存储与读取。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("recommend_candidate_video")
public class CandidateVideoPO {

    /**
     * 候选记录全局唯一主键 ID。
     *
     * <p>业务含义与约束说明：
     * <ul>
     *   <li><b>格式规范</b>：32 位无连字符标准 UUID 字符串；</li>
     *   <li><b>主键策略</b>：由应用层在消费发布上线事件准入推荐池时显式分配注入，采用 {@link IdType#INPUT} 模式，非自增；</li>
     *   <li><b>物料账本</b>：作为推荐候选池库存表的物理主键，与 {@code video_id} 唯一键配合保证单视频在候选池中唯一的可用状态账本。</li>
     * </ul>
     * </p>
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 视频内部全局主键 ID。 */
    @TableField("video_id")
    private String videoId;

    /** 业务公开短码 vid。 */
    @TableField("vid")
    private String vid;

    /** 创作者账号 ID。 */
    @TableField("author_id")
    private String authorId;

    /** 领域标签 ID 列表字符串。 */
    @TableField("domain_tag_ids")
    private String domainTagIds;

    /** 主题标签 ID 列表字符串。 */
    @TableField("topic_tag_ids")
    private String topicTagIds;

    /** 候选池状态 (ACTIVE / OFFLINE / BANNED)。 */
    @TableField("status")
    private String status;

    /** 正式公开发布时间。 */
    @TableField("published_at")
    private LocalDateTime publishedAt;

    /** 记录创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 记录最后更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 将持久化对象转换为领域实体。
     *
     * @return 对应的 {@link CandidateVideo} 领域实体
     */
    public CandidateVideo toDomain() {
        return CandidateVideo.builder()
                .id(this.id)
                .videoId(this.videoId)
                .vid(this.vid)
                .authorId(this.authorId)
                .domainTagIds(this.domainTagIds)
                .topicTagIds(this.topicTagIds)
                .status(this.status != null ? CandidateStatus.fromCode(this.status) : CandidateStatus.ACTIVE)
                .publishedAt(this.publishedAt)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .build();
    }

    /**
     * 从领域实体构建持久化对象。
     *
     * @param domain {@link CandidateVideo} 领域实体
     * @return 转换后的 {@link CandidateVideoPO}
     */
    public static CandidateVideoPO fromDomain(CandidateVideo domain) {
        if (domain == null) {
            return null;
        }
        return CandidateVideoPO.builder()
                .id(domain.getId())
                .videoId(domain.getVideoId())
                .vid(domain.getVid())
                .authorId(domain.getAuthorId())
                .domainTagIds(domain.getDomainTagIds())
                .topicTagIds(domain.getTopicTagIds())
                .status(domain.getStatus() != null ? domain.getStatus().getCode() : CandidateStatus.ACTIVE.getCode())
                .publishedAt(domain.getPublishedAt())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }
}
