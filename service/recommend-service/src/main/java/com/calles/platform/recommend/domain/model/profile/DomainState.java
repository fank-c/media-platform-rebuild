package com.calles.platform.recommend.domain.model.profile;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 粗粒度领域状态值对象。
 *
 * <p>维护用户对全站宏观领域（DOMAIN）的曝光未消费状态。
 * 当某大类连续曝光且全部滑过时，未消费计数累加，用于宏观多样性打散与疲劳抑制打折；
 * 用户重新消费该领域内容时计数清零复原。</p>
 */
@Getter
public class DomainState {

    /** 触发弱负向惩罚的连续未消费门限次数。 */
    public static final int SUPPRESSION_THRESHOLD = 3;

    /** 粗领域标签业务唯一标识 ID。 */
    private final String domainId;

    /** 连续曝光未消费累计次数。 */
    private final int exposureCount;

    /** 最后一次交互或曝光时间。 */
    private final LocalDateTime lastActiveAt;

    public DomainState(String domainId, int exposureCount, LocalDateTime lastActiveAt) {
        this.domainId = Objects.requireNonNull(domainId, "领域ID不能为空");
        this.exposureCount = Math.max(0, exposureCount);
        this.lastActiveAt = lastActiveAt != null ? lastActiveAt : LocalDateTime.now();
    }

    /**
     * 工厂方法：初始化未消费状态。
     */
    public static DomainState initial(String domainId) {
        return new DomainState(domainId, 1, LocalDateTime.now());
    }

    /**
     * 记录一次未消费曝光（如用户快速滑过），未消费计数加 1。
     */
    public DomainState recordUnconsumed() {
        return new DomainState(this.domainId, this.exposureCount + 1, LocalDateTime.now());
    }

    /**
     * 记录一次有效消费（如完播或点击），未消费计数清零恢复正常。
     */
    public DomainState recordConsumed() {
        return new DomainState(this.domainId, 0, LocalDateTime.now());
    }

    /**
     * 计算该领域的当前推荐惩罚折扣系数 (0.0 ~ 1.0)。
     *
     * <p>未达到门限时返回 1.0 (不打折)；
     * 连续 3 次未消费打 8 折 (0.8)；
     * 连续 5 次及以上未消费打 6 折 (0.6)。</p>
     *
     * @return 打折系数
     */
    public double calculateSuppressionFactor() {
        if (exposureCount < SUPPRESSION_THRESHOLD) {
            return 1.0;
        } else if (exposureCount < 5) {
            return 0.8;
        } else {
            return 0.6;
        }
    }
}
