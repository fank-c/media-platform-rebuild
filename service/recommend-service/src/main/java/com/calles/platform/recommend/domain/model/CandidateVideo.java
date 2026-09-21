package com.calles.platform.recommend.domain.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 推荐候选视频领域实体 (CandidateVideo)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：推荐候选池（Candidate Pool）核心聚合实体；</li>
 *   <li><b>核心用途</b>：承接发布上线的视频，维护其作者打散维度、领域/主题标签属性以及推荐可用状态；</li>
 *   <li><b>纯粹性约束</b>：只维护分发、打散与重排真正依赖的核心属性，不冗余存储大图文与长媒体流资产。</li>
 * </ul>
 * </p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateVideo {

    /** 候选记录全局主键 ID (UUID 32位无短横线)。 */
    private String id;

    /** 视频内部全局主键 ID (对应 content_service 的 video_content.id)。 */
    private String videoId;

    /** 视频公开业务短码 (Base62，如 cv05hG9Kq2RtLw7XbPmZv4Ya，最终下发给客户端)。 */
    private String vid;

    /** 创作者账号 ID (用于推荐重排同作者打散防连续刷出)。 */
    private String authorId;

    /** 关联领域标签 ID 列表 (逗号分隔文本，用于多样性打散与弱负向惩罚)。 */
    private String domainTagIds;

    /** 关联主题标签 ID 列表 (逗号分隔文本，用于精排微调加分与推荐解释)。 */
    private String topicTagIds;

    /** 推荐准入状态 (ACTIVE=可推荐, OFFLINE=已下线, BANNED=已封禁)。 */
    private CandidateStatus status;

    /** 视频正式公开发布时间 (用于时效性特征加权与新鲜度保底)。 */
    private LocalDateTime publishedAt;

    /** 候选入库时间。 */
    private LocalDateTime createdAt;

    /** 候选记录最后更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 工厂方法：初始化正式发布上线的候选视频（默认状态为 ACTIVE）。
     *
     * @param id 预生成的 UUID 主键
     * @param videoId 视频内部 ID
     * @param vid 视频对外公开短码
     * @param authorId 创作者账号 ID
     * @param domainTagIds 领域标签 ID 列表字符串
     * @param topicTagIds 主题标签 ID 列表字符串
     * @param publishedAt 正式发布时间戳
     * @return 处于 ACTIVE 状态的推荐候选实体
     * @throws IllegalArgumentException 当核心必要标识为空时抛出
     */
    public static CandidateVideo createPublished(
            String id,
            String videoId,
            String vid,
            String authorId,
            String domainTagIds,
            String topicTagIds,
            LocalDateTime publishedAt) {

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("候选记录ID不能为空");
        }
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("视频内部ID不能为空");
        }
        if (vid == null || vid.isBlank()) {
            throw new IllegalArgumentException("视频业务短码不能为空");
        }
        if (authorId == null || authorId.isBlank()) {
            throw new IllegalArgumentException("创作者ID不能为空");
        }

        return CandidateVideo.builder()
                .id(id)
                .videoId(videoId.trim())
                .vid(vid.trim())
                .authorId(authorId.trim())
                .domainTagIds(domainTagIds != null ? domainTagIds.trim() : "")
                .topicTagIds(topicTagIds != null ? topicTagIds.trim() : "")
                .status(CandidateStatus.ACTIVE)
                .publishedAt(publishedAt != null ? publishedAt : LocalDateTime.now())
                .build();
    }

    /**
     * 将候选视频状态变更为已下线 (OFFLINE)。
     */
    public void markOffline() {
        this.status = CandidateStatus.OFFLINE;
    }

    /**
     * 将候选视频状态变更为已封禁 (BANNED)。
     */
    public void markBanned() {
        this.status = CandidateStatus.BANNED;
    }

    /**
     * 重新激活候选视频为正常推荐状态 (ACTIVE)。
     */
    public void activate() {
        this.status = CandidateStatus.ACTIVE;
    }

    /**
     * 判定当前候选视频是否具备推荐准入资格。
     *
     * @return true 表示处于 ACTIVE 状态，允许被多路召回与分发
     */
    public boolean isRecommendable() {
        return this.status == CandidateStatus.ACTIVE;
    }
}
