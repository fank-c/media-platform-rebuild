package com.calles.platform.interaction.domain.model.watch;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 心跳可信度校验策略（纯领域计算，无外部依赖）。
 *
 * <p>职责边界：把客户端上报的增量换算成服务端认可的有效观看增量。
 * 客户端无法自证是否真的在观看，因此增量必须同时受三个上限约束：</p>
 *
 * <ol>
 *   <li><b>单次上限</b>：不超过 {@code maxDelta}，防止一次心跳刷入大量时长；</li>
 *   <li><b>时间上限</b>：不超过服务端两次心跳之间的真实间隔加容差，防止篡改增量绕过单次上限；</li>
 *   <li><b>会话上限</b>：不超过本会话视频时长剩余量，防止累计时长超过视频本身。</li>
 * </ol>
 *
 * <p>播放位置只用于断点续播，跳跃距离不计入观看时长；时长口径只来自校验后的增量。</p>
 */
public final class WatchCreditValidator {

    private WatchCreditValidator() {
    }

    /**
     * 计算本次心跳服务端认可的有效观看增量。
     *
     * @param clientDelta 客户端上报增量 (秒)，允许为空或非正数
     * @param lastHeartbeatAt 上一次有效心跳时间，为空表示本会话首次心跳
     * @param now 当前服务端时间
     * @param maxDelta 单次增量上限
     * @param tolerance 时间校验容差
     * @param durationSnapshot 本会话固定使用的视频时长快照 (秒)，小于等于 0 表示无有效快照，不施加会话上限
     * @param creditedSoFar 本会话此前已累计的有效观看时长 (秒)
     * @return 认可的有效观看增量 (秒)，恒大于等于 0
     */
    public static int resolveCreditedDelta(Integer clientDelta,
                                           LocalDateTime lastHeartbeatAt,
                                           LocalDateTime now,
                                           Duration maxDelta,
                                           Duration tolerance,
                                           int durationSnapshot,
                                           int creditedSoFar) {
        if (clientDelta == null || clientDelta <= 0) {
            return 0;
        }

        long maxDeltaSeconds = maxDelta != null ? maxDelta.toSeconds() : 15L;
        long toleranceSeconds = tolerance != null ? tolerance.toSeconds() : 0L;

        // 步骤 1：按服务端时间差推导本次允许计入的时间上限
        long elapsedSeconds = 0L;
        if (lastHeartbeatAt != null && now != null) {
            elapsedSeconds = Math.max(0L, Duration.between(lastHeartbeatAt, now).toSeconds());
        }
        long timeAllowance = elapsedSeconds + toleranceSeconds;

        // 步骤 2：同时受单次上限与时间上限约束
        long credited = Math.min(clientDelta, Math.min(Math.max(1L, maxDeltaSeconds), timeAllowance));

        // 步骤 3：有有效时长快照时，受剩余可计入秒数约束，防止累计时长超出视频本身
        if (durationSnapshot > 0) {
            int remaining = durationSnapshot - Math.max(0, creditedSoFar);
            if (remaining <= 0) {
                return 0;
            }
            credited = Math.min(credited, remaining);
        }

        return (int) Math.max(0L, credited);
    }

    /**
     * 判断本次心跳的播放位置是否出现大幅前跳。
     *
     * <p>该方法只用于可观测性与告警，不影响时长口径：跳跃距离本身从不计入有效观看时长。</p>
     *
     * @param previousPosition 上一次播放位置 (秒)
     * @param position 本次播放位置 (秒)
     * @param creditedDelta 本次认可的有效观看增量 (秒)
     * @param tolerance 允许的位置与增量偏差 (秒)
     * @return true 表示位置前跳幅度明显超过本次增量
     */
    public static boolean isSuspiciousForwardJump(int previousPosition, int position,
                                                  int creditedDelta, Duration tolerance) {
        long toleranceSeconds = tolerance != null ? tolerance.toSeconds() : 0L;
        long positionAdvance = (long) position - previousPosition;
        return positionAdvance > creditedDelta + toleranceSeconds;
    }
}
