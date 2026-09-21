package com.calles.platform.recommend.domain.model.feedback;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 行为反馈流水领域实体。
 *
 * <p>表示由客户端直接上报并经过门禁核验的客观行为事实记录。
 * 遵循不可篡改与只追加原则，为画像模型重算、审计追溯和离线对账提供底层事实账本。</p>
 */
@Getter
public class FeedbackLog {

    /** 流水唯一主键 UUID (32位无短横线)。 */
    private final String id;

    /** 发生行为的用户全局唯一标识。 */
    private final String userId;

    /** 交互的视频业务公开短码。 */
    private final String vid;

    /** 具体的交互行为动作类型。 */
    private final FeedbackActionType actionType;

    /** 实际播放时长 (秒)。 */
    private final int playDuration;

    /** 视频总时长 (秒)。 */
    private final int videoDuration;

    /** 发生行为时视频的粗领域标签ID快照 (逗号分隔)。 */
    private final String domainTagIds;

    /** 发生行为时视频的细主题标签ID快照 (逗号分隔)。 */
    private final String topicTagIds;

    /** 发生行为时视频创作者ID快照。 */
    private final String authorId;

    /** 全链路追踪 ID。 */
    private final String traceId;

    /** 客户端真实行为发生时间戳。 */
    private final LocalDateTime occurredAt;

    /** 流水写入库时间戳。 */
    private final LocalDateTime createdAt;

    /**
     * 全参构造方法，用于仓储还原。
     */
    public FeedbackLog(String id, String userId, String vid, FeedbackActionType actionType,
                       int playDuration, int videoDuration, String domainTagIds, String topicTagIds,
                       String authorId, String traceId, LocalDateTime occurredAt, LocalDateTime createdAt) {
        this.id = Objects.requireNonNull(id, "流水ID不能为空");
        this.userId = Objects.requireNonNull(userId, "用户ID不能为空");
        this.vid = Objects.requireNonNull(vid, "视频短码不能为空");
        this.actionType = Objects.requireNonNull(actionType, "行为类型不能为空");
        this.playDuration = Math.max(0, playDuration);
        this.videoDuration = Math.max(0, videoDuration);
        this.domainTagIds = domainTagIds;
        this.topicTagIds = topicTagIds;
        this.authorId = authorId;
        this.traceId = traceId;
        this.occurredAt = occurredAt != null ? occurredAt : LocalDateTime.now();
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    /**
     * 工厂方法：新建一笔行为反馈事实记录。
     */
    public static FeedbackLog record(String userId, String vid, FeedbackActionType actionType,
                                     int playDuration, int videoDuration, String domainTagIds, String topicTagIds,
                                     String authorId, String traceId, LocalDateTime occurredAt) {
        String generatedId = UUID.randomUUID().toString().replace("-", "");
        return new FeedbackLog(
                generatedId,
                userId,
                vid,
                actionType,
                playDuration,
                videoDuration,
                domainTagIds,
                topicTagIds,
                authorId,
                traceId,
                occurredAt != null ? occurredAt : LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    /**
     * 计算该次行为的完播比例 (0.0 ~ 1.0)。
     *
     * @return 完播比例浮点数
     */
    public double calculatePlayRatio() {
        if (videoDuration <= 0) {
            return 0.0;
        }
        return Math.min(1.0, (double) playDuration / videoDuration);
    }
}
