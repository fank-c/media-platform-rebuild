package com.calles.platform.interaction.domain.model.watch;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 观看会话领域实体。
 *
 * <p>职责边界：累计"本次会话"服务端校验后的有效观看时长，并保存会话内的门槛与序号状态。
 * 播放量与完播判定只读取本实体的 {@code creditedDuration}，不读取播放位置推算的时长。</p>
 *
 * <p>关键约束：{@code durationSnapshot} 与 {@code qualificationThreshold} 在会话创建时一次性固定，
 * 之后即使视频元数据变化，本次会话的门槛也不发生漂移。</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WatchSession {

    /** 会话主键 UUID，由服务端首次心跳生成。 */
    private String sessionId;

    /** 观看用户账号 ID。 */
    private String userId;

    /** 目标视频公开短码。 */
    private String vid;

    /** 会话创建时的视频时长快照 (秒)。 */
    private int durationSnapshot;

    /** 会话创建时固定的播放量门槛 (秒)。 */
    private int qualificationThreshold;

    /** 本会话服务端认可的有效观看累计时长 (秒)。 */
    private int creditedDuration;

    /** 已处理的最大客户端心跳序号，为空表示客户端未提供序号。 */
    private Long lastSequence;

    /** 本会话最近一次播放位置 (秒)。 */
    private int lastPosition;

    /** 本会话是否已达到合格观看事件门槛。 */
    private boolean qualified;

    /** 播放量入账时间，非空表示该会话已记入播放量；为空表示未计数。 */
    private LocalDateTime viewCountedAt;

    /** 起播请求幂等键，新会话必填，用于起播防重。 */
    private String startRequestKey;

    /** 会话开始时间。 */
    private LocalDateTime startedAt;

    /** 最近一次有效心跳时间。 */
    private LocalDateTime lastHeartbeatAt;

    /** 会话关闭时间，为空表示仍处于活跃状态。 */
    private LocalDateTime closedAt;

    /**
     * 开启一个新的观看会话。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param startRequestKey 起播请求幂等键
     * @param durationSnapshot 会话固定的视频时长快照 (秒)
     * @param qualificationThreshold 会话固定的合格观看事件门槛 (秒)
     * @param position 本会话起始播放位置 (秒)
     * @param now 当前业务时间
     * @return 新建的活跃会话
     */
    public static WatchSession open(String userId, String vid, String startRequestKey,
                                    int durationSnapshot, int qualificationThreshold,
                                    int position, LocalDateTime now) {
        return WatchSession.builder()
                .sessionId(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId)
                .vid(vid)
                .startRequestKey(startRequestKey)
                .durationSnapshot(durationSnapshot)
                .qualificationThreshold(qualificationThreshold)
                .creditedDuration(0)
                .lastSequence(null)
                .lastPosition(Math.max(0, position))
                .qualified(false)
                .viewCountedAt(null)
                .startedAt(now)
                .lastHeartbeatAt(now)
                .closedAt(null)
                .build();
    }

    /**
     * 便捷重载：开启一个未指定起播幂等键的观看会话（用于测试或降级场景）。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param durationSnapshot 会话固定的视频时长快照 (秒)
     * @param qualificationThreshold 会话固定的合格观看事件门槛 (秒)
     * @param position 本会话起始播放位置 (秒)
     * @param now 当前业务时间
     * @return 新建的活跃会话
     */
    public static WatchSession open(String userId, String vid, int durationSnapshot,
                                    int qualificationThreshold, int position, LocalDateTime now) {
        return open(userId, vid, null, durationSnapshot, qualificationThreshold, position, now);
    }

    /**
     * 标记当前会话已计入播放量。
     *
     * @param now 计入生效时间
     */
    public void markViewCounted(LocalDateTime now) {
        this.viewCountedAt = now;
    }

    /**
     * 判断当前会话是否已计入播放量。
     *
     * @return true 表示已计入播放量
     */
    public boolean isViewCounted() {
        return this.viewCountedAt != null;
    }

    /**
     * 判断会话是否已因超过超时时间而结束。
     *
     * @param now 当前业务时间
     * @param sessionTimeout 会话超时时间
     * @return true 表示应关闭本会话并开启新会话
     */
    public boolean isExpired(LocalDateTime now, Duration sessionTimeout) {
        Duration timeout = sessionTimeout != null ? sessionTimeout : Duration.ofMinutes(30);
        return lastHeartbeatAt.plus(timeout).isBefore(now) || lastHeartbeatAt.plus(timeout).isEqual(now);
    }

    /**
     * 判断本次心跳序号是否重复或过期，从而必须跳过时长累计。
     *
     * <p>序号小于等于已处理最大值时，说明该请求是重复投递或乱序到达，直接返回当前状态即可。</p>
     *
     * @param sequence 本次请求携带的序号，允许为空
     * @return true 表示跳过时长累计与断点更新
     */
    public boolean isReplayOrStale(Long sequence) {
        return sequence != null && lastSequence != null && sequence <= lastSequence;
    }

    /**
     * 判断本次心跳序号是否恰好等于已处理的最大序号（严格重复投递）。
     *
     * @param sequence 本次请求携带的序号，允许为空
     * @return true 表示严格重复
     */
    public boolean isExactReplay(Long sequence) {
        return sequence != null && lastSequence != null && sequence.equals(lastSequence);
    }

    /**
     * 记录一次有效心跳的累计结果。
     *
     * @param creditedDelta 本次认可的有效观看增量 (秒)
     * @param position 本次播放位置 (秒)
     * @param sequence 本次心跳序号，允许为空
     * @param now 当前业务时间
     */
    public void recordHeartbeat(int creditedDelta, int position, Long sequence, LocalDateTime now) {
        this.creditedDuration = Math.max(0, this.creditedDuration) + Math.max(0, creditedDelta);
        this.lastPosition = Math.max(0, position);
        this.lastHeartbeatAt = now;
        if (sequence != null && (this.lastSequence == null || sequence > this.lastSequence)) {
            this.lastSequence = sequence;
        }
    }

    /**
     * 根据会话有效观看时长刷新资格状态。
     *
     * @return true 表示本次调用使会话由未达标变为达标
     */
    public boolean refreshQualification() {
        if (this.qualified) {
            return false;
        }
        if (WatchQualificationPolicy.isQualified(this.creditedDuration, this.qualificationThreshold)) {
            this.qualified = true;
            return true;
        }
        return false;
    }

    /**
     * 关闭会话并记录关闭时间。
     *
     * @param now 当前业务时间
     */
    public void close(LocalDateTime now) {
        this.closedAt = now;
    }
}
