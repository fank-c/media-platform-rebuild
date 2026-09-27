package com.calles.platform.interaction.domain.model.watch;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 观看进度领域实体。
 *
 * <p>职责边界：只承担断点续播、历史展示与最近一次播放量时间三项数据。
 * 会话累计、播放资格与事件防重分别由 {@link WatchSession} 和 {@link WatchEventClaim} 承担。</p>
 *
 * <p>删除语义：删除历史只把 {@code deleted} 置 1 隐藏展示，记录不物理删除，
 * 因此播放量冷却时间不会被"删除后重新观看"重置。</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WatchProgress {

    /** 记录主键 UUID。 */
    private String id;

    /** 观看用户账号 ID。 */
    private String userId;

    /** 目标视频公开短码。 */
    private String vid;

    /** 当前活跃会话 ID，无活跃会话时为空。 */
    private String activeSessionId;

    /** 上次播放头断点位置 (秒)。 */
    private int lastPosition;

    /** 累计有效观看总时长 (秒)，跨会话累加。 */
    private int watchedDuration;

    /** 首次观看时间。 */
    private LocalDateTime firstWatchAt;

    /** 最近一次心跳活跃时间。 */
    private LocalDateTime lastWatchAt;

    /** 最近一次成功计入播放量的时间，用于重复播放冷却判断。 */
    private LocalDateTime lastViewClaimedAt;

    /** 是否已隐藏历史展示：1=已删除(隐藏)，0=正常展示。 */
    private boolean deleted;

    /**
     * 初始化一条全新的观看进度。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param now 当前业务时间
     * @return 初始化的观看进度实体
     */
    public static WatchProgress create(String userId, String vid, LocalDateTime now) {
        return WatchProgress.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId)
                .vid(vid)
                .lastPosition(0)
                .watchedDuration(0)
                .firstWatchAt(now)
                .lastWatchAt(now)
                .lastViewClaimedAt(null)
                .deleted(false)
                .build();
    }

    /**
     * 绑定当前活跃会话。
     *
     * @param sessionId 会话 ID
     */
    public void attachSession(String sessionId) {
        this.activeSessionId = sessionId;
    }

    /**
     * 记录一次心跳带来的断点与有效时长变化。
     *
     * <p>断点取本次上报位置；有效时长只累加服务端校验后的增量。
     * 若记录此前被隐藏，本方法顺带恢复展示，但不会重置任何防重时间。</p>
     *
     * @param position 本次播放位置 (秒)
     * @param creditedDelta 服务端认可的有效观看增量 (秒)
     * @param now 当前业务时间
     */
    public void recordHeartbeat(int position, int creditedDelta, LocalDateTime now) {
        this.lastPosition = Math.max(0, position);
        this.watchedDuration = Math.max(0, this.watchedDuration) + Math.max(0, creditedDelta);
        this.lastWatchAt = now;
        this.deleted = false;
    }

    /**
     * 标记本次会话已成功计入播放量。
     *
     * @param now 计入时间
     */
    public void markViewClaimed(LocalDateTime now) {
        this.lastViewClaimedAt = now;
    }
}
