package com.calles.platform.interaction.domain.model.watch;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 播放量与完播判定策略（纯领域计算，无外部依赖）。
 *
 * <p>职责边界：只回答"是否达到门槛"与"是否完播"两个业务问题，不负责持久化、事件发布与计数副作用。</p>
 */
public final class WatchQualificationPolicy {

    private WatchQualificationPolicy() {
    }

    /**
     * 计算会话合格观看事件门槛。
     *
     * <p>门槛取 {@code max(最低时长门槛, ceil(视频时长 × 资格比例))}，短视频因此不会被极短观看就计为合格观看。
     * 视频时长无效 (小于等于 0) 时返回 0，调用方必须据此跳过全部资格判定。</p>
     *
     * @param duration 会话固定的视频时长快照 (秒)
     * @param minThreshold 最低观看时长门槛
     * @param ratio 资格比例 (0.30 表示 30%)
     * @return 本会话合格观看事件门槛 (秒)
     */
    public static int resolveQualificationThreshold(int duration, Duration minThreshold, double ratio) {
        if (duration <= 0) {
            return 0;
        }
        long minSeconds = minThreshold != null ? minThreshold.toSeconds() : 0L;
        long ratioSeconds = (long) Math.ceil(duration * ratio);
        return (int) Math.max(minSeconds, ratioSeconds);
    }

    /**
     * 判断本会话有效观看时长是否达到合格观看事件门槛。
     *
     * @param creditedDuration 本会话服务端认可的有效观看时长 (秒)
     * @param threshold 本会话合格观看事件门槛 (秒)
     * @return true 表示达到门槛
     */
    public static boolean isQualified(int creditedDuration, int threshold) {
        return threshold > 0 && creditedDuration >= threshold;
    }

    /**
     * 判断本次心跳是否达成完播条件。
     *
     * <p>必须同时满足：播放位置达到视频时长比例门槛、有效观看时长达到同一比例门槛。</p>
     *
     * @param position 当前播放位置 (秒)
     * @param creditedDuration 本会话服务端认可的有效观看时长 (秒)
     * @param duration 会话固定的视频时长快照 (秒)
     * @param ratio 完播比例门槛 (0.90 表示 90%)
     * @return true 表示达成完播
     */
    public static boolean isCompleted(int position, int creditedDuration, int duration, double ratio) {
        if (duration <= 0) {
            return false;
        }
        long required = (long) Math.ceil(duration * ratio);
        return position >= required && creditedDuration >= required;
    }

    /**
     * 计算存在冷却窗口时的资格可用性。
     *
     * @param lastViewClaimedAt 最近一次成功计入播放量的时间，为空表示从未计入
     * @param now 当前时间
     * @param repeatWindow 重复播放冷却窗口
     * @return true 表示冷却已结束，本会话允许计入一次播放量
     */
    public static boolean isCooldownElapsed(LocalDateTime lastViewClaimedAt,
                                            LocalDateTime now,
                                            Duration repeatWindow) {
        if (lastViewClaimedAt == null) {
            return true;
        }
        Duration window = repeatWindow != null ? repeatWindow : Duration.ZERO;
        return !lastViewClaimedAt.plus(window).isAfter(now);
    }
}
