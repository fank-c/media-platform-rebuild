package com.calles.platform.recommend.domain.model.profile;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 细粒度主题偏好值对象。
 *
 * <p>记录用户对具体主题标签（TOPIC）的正向偏好分值与最后活跃时间。
 * 遵循对数饱和上限，用于精排阶段的个性化微调提分和推荐理由解释。</p>
 */
@Getter
public class TopicPreference {

    /** 默认单标签分值上限。 */
    public static final double DEFAULT_MAX_SCORE = 5.0;

    /** 默认单次正向消费递增步长。 */
    public static final double DEFAULT_INCREMENT = 0.5;

    /** 主题标签业务唯一标识 ID。 */
    private final String tagId;

    /** 当前累积偏好得分 (0.0 ~ 5.0)。 */
    private final double score;

    /** 最后活跃时间。 */
    private final LocalDateTime lastActiveAt;

    public TopicPreference(String tagId, double score, LocalDateTime lastActiveAt) {
        this.tagId = Objects.requireNonNull(tagId, "主题标签ID不能为空");
        this.score = Math.max(0.0, score);
        this.lastActiveAt = lastActiveAt != null ? lastActiveAt : LocalDateTime.now();
    }

    /**
     * 工厂方法：为新主题标签初始化首笔偏好分。
     */
    public static TopicPreference initial(String tagId) {
        return new TopicPreference(tagId, DEFAULT_INCREMENT, LocalDateTime.now());
    }

    /**
     * 累加正向消费得分，受制于封顶上限。
     *
     * @param increment 递增步长
     * @param maxScore 封顶分值
     * @return 递增后的新 TopicPreference 实例
     */
    public TopicPreference increment(double increment, double maxScore) {
        double newScore = Math.min(maxScore, this.score + increment);
        return new TopicPreference(this.tagId, newScore, LocalDateTime.now());
    }

    /**
     * 按照默认参数递增得分。
     */
    public TopicPreference increment() {
        return increment(DEFAULT_INCREMENT, DEFAULT_MAX_SCORE);
    }
}
