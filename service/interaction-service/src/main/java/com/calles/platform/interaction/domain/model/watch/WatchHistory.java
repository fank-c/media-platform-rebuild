package com.calles.platform.interaction.domain.model.watch;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 用户视频观看历史与心跳进度领域实体。
 *
 * <p>维护用户在具体视频上的断点续播位置、累计有效观看时长与完播状态，由播放端心跳（Heartbeat）驱动更新。</p>
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WatchHistory {

    /** 完播判定阈值比例：播放头达到总时长的 90% 视为完播。 */
    public static final double COMPLETION_THRESHOLD_RATIO = 0.90;

    /** 观看记录主键 ID (UUID)。 */
    private String id;

    /** 观看用户账号 ID。 */
    private String userId;

    /** 目标视频公开短码。 */
    private String vid;

    /** 上次播放进度断点位置 (秒)，用于断点续播。 */
    private int lastPosition;

    /** 累计有效观看时长 (秒)。 */
    private int watchedDuration;

    /** 视频总时长 (秒)。 */
    private int videoDuration;

    /** 是否已完播：true=是, false=否。 */
    private boolean completed;

    /** 首次观看时间。 */
    private LocalDateTime firstWatchAt;

    /** 最近一次心跳上报活跃时间。 */
    private LocalDateTime lastWatchAt;

    /** 上次计为有效播放并写入 Outbox 的时间戳 (持久化防重依据)。 */
    private LocalDateTime lastValidPlayAt;

    /** 是否已逻辑删除：true=已删除, false=正常有效。 */
    private boolean deleted;

    /**
     * 工厂方法：首次产生观看行为时新建历史记录。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param initialPosition 初始进度 (秒)
     * @param deltaDuration 本次增量时长 (秒)
     * @param videoDuration 视频总时长 (秒)
     * @return 初始化的观看实体
     */
    public static WatchHistory create(String userId, String vid, int initialPosition,
                                      int deltaDuration, int videoDuration) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户账号ID不能为空");
        }
        if (vid == null || vid.isBlank()) {
            throw new IllegalArgumentException("视频业务短码不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        int safePos = Math.max(0, initialPosition);
        int safeDelta = Math.max(0, deltaDuration);
        int safeTotal = Math.max(0, videoDuration);

        boolean isCompleted = safeTotal > 0 && safePos >= (int) (safeTotal * COMPLETION_THRESHOLD_RATIO);

        return WatchHistory.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId.trim())
                .vid(vid.trim())
                .lastPosition(safePos)
                .watchedDuration(safeDelta)
                .videoDuration(safeTotal)
                .completed(isCompleted)
                .firstWatchAt(now)
                .lastWatchAt(now)
                .build();
    }

    /**
     * 接收心跳更新播放头位置与累计时长。
     *
     * @param position 当前播放头所在秒数
     * @param deltaDuration 距上次心跳增量秒数
     * @param videoDuration 视频总时长
     */
    public void recordHeartbeat(int position, int deltaDuration, int videoDuration) {
        this.lastPosition = Math.max(0, position);
        if (deltaDuration > 0) {
            this.watchedDuration += deltaDuration;
        }
        if (videoDuration > 0) {
            this.videoDuration = videoDuration;
        }
        if (!this.completed && this.videoDuration > 0
                && this.lastPosition >= (int) (this.videoDuration * COMPLETION_THRESHOLD_RATIO)) {
            this.completed = true;
        }
        this.lastWatchAt = LocalDateTime.now();
    }

    /**
     * 判断当前是否满足窗口期内的首次有效播放持久化条件。
     *
     * @param now 当前时间戳
     * @param window 防刷时间窗口
     * @return true 若为新窗口首次有效播放
     */
    public boolean shouldCountValidPlay(LocalDateTime now, java.time.Duration window) {
        if (this.lastValidPlayAt == null) {
            return true;
        }
        return this.lastValidPlayAt.plus(window).isBefore(now);
    }

    /**
     * 标记本次达成有效播放并更新持久化时间戳。
     *
     * @param now 当前时间戳
     */
    public void markValidPlay(LocalDateTime now) {
        this.lastValidPlayAt = now;
    }

    /**
     * 逻辑删除观看历史记录（用户主动删除历史记录，但底层持久化保留以维护防重与风控审计）。
     */
    public void markDeleted() {
        this.deleted = true;
    }

    /**
     * 再次播放时自愈复活已逻辑删除的记录，重置断点并继续累计，但严格保留原 lastValidPlayAt 防重时间戳。
     *
     * @param position 当前播放头所在秒数
     * @param deltaDuration 增量时长 (秒)
     * @param videoDuration 视频总时长 (秒)
     */
    public void revive(int position, int deltaDuration, int videoDuration) {
        this.deleted = false;
        this.lastPosition = Math.max(0, position);
        this.watchedDuration = Math.max(0, deltaDuration);
        if (videoDuration > 0) {
            this.videoDuration = videoDuration;
        }
        this.completed = this.videoDuration > 0 && this.lastPosition >= (int) (this.videoDuration * COMPLETION_THRESHOLD_RATIO);
        this.lastWatchAt = LocalDateTime.now();
    }
}
