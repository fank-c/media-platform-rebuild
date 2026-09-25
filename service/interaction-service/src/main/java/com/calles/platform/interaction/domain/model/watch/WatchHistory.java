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

    /** 下次播放事件资格判定门槛比例：会话有效观看达到总时长的 30% 视为具备资格。 */
    public static final double QUALIFIED_THRESHOLD_RATIO = 0.30;

    /** 观看记录主键 ID (UUID)。 */
    private String id;

    /** 观看用户账号 ID。 */
    private String userId;

    /** 目标视频公开短码。 */
    private String vid;

    /** 上次播放进度断点位置 (秒)，用于断点续播。 */
    private int lastPosition;

    /** 历史累计有效观看总时长 (秒)。 */
    private int watchedDuration;

    /** 当前观看会话累计有效观看时长 (秒)。 */
    private int sessionWatchedDuration;

    /** 当前会话是否已经发送播放事件：true=已发送, false=未发送。 */
    private boolean sessionPlayEmitted;

    /** 上一会话是否达到 30% 消费门槛，允许下一次会话在满足条件时触发播放事件。 */
    private boolean eligibleForNextPlay;

    /** 视频总时长 (秒)。 */
    private int videoDuration;

    /** 是否已完播：true=是, false=否。 */
    private boolean completed;

    /** 首次观看时间。 */
    private LocalDateTime firstWatchAt;

    /** 最近一次活跃观看时间（起播或心跳上报时间）。 */
    private LocalDateTime lastWatchAt;

    /** 最近一次计入有效播放并生成播放事件的时间戳。 */
    private LocalDateTime lastValidPlayAt;

    /** 是否已逻辑删除：true=已删除, false=正常有效。 */
    private boolean deleted;

    /**
     * 工厂方法：用户首次点进视频起播时初始化历史记录。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return 初始化的观看实体
     */
    public static WatchHistory createForPlay(String userId, String vid) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户账号ID不能为空");
        }
        if (vid == null || vid.isBlank()) {
            throw new IllegalArgumentException("视频业务短码不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        return WatchHistory.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId.trim())
                .vid(vid.trim())
                .lastPosition(0)
                .watchedDuration(0)
                .sessionWatchedDuration(0)
                .sessionPlayEmitted(false)
                .eligibleForNextPlay(false)
                .videoDuration(0)
                .completed(false)
                .firstWatchAt(now)
                .lastWatchAt(now)
                .deleted(false)
                .build();
    }

    /**
     * 工厂方法：心跳保底新建历史记录。
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

        WatchHistory history = WatchHistory.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .userId(userId.trim())
                .vid(vid.trim())
                .lastPosition(safePos)
                .watchedDuration(safeDelta)
                .sessionWatchedDuration(safeDelta)
                .sessionPlayEmitted(false)
                .eligibleForNextPlay(false)
                .videoDuration(safeTotal)
                .completed(false)
                .firstWatchAt(now)
                .lastWatchAt(now)
                .deleted(false)
                .build();
        history.markEligibleForNextPlayIfSessionQualified();
        return history;
    }

    /**
     * 接收播放心跳：更新播放头断点位置与累计时长（完播状态由外部 CAS 原子保障驱动）。
     *
     * @param position 当前播放头所在秒数
     * @param deltaDuration 距上次心跳增量秒数
     * @param videoDuration 视频总时长
     */
    public void recordHeartbeat(int position, int deltaDuration, int videoDuration) {
        this.lastPosition = Math.max(0, position);
        if (deltaDuration > 0) {
            this.watchedDuration += deltaDuration;
            this.sessionWatchedDuration += deltaDuration;
        }
        if (videoDuration > 0) {
            this.videoDuration = videoDuration;
        }
        this.lastWatchAt = LocalDateTime.now();
        markEligibleForNextPlayIfSessionQualified();
    }

    /**
     * 判断当前会话是否达到视频总时长 30% 的消费门槛。
     *
     * <p>视频总时长未知时不产生下一会话播放资格，避免仅凭心跳时长错误放行。</p>
     *
     * @return true 表示当前会话已达到资格门槛
     */
    public boolean isSessionQualified() {
        return this.videoDuration > 0
                && this.sessionWatchedDuration >= qualifiedSessionDuration(this.videoDuration);
    }

    /**
     * 依据当前会话的时长更新下一会话播放资格。
     *
     * <p>资格一经达成只会置为 true；新会话开始时再根据上一会话结果统一重置，避免同一会话内重复计算比例。</p>
     */
    public void markEligibleForNextPlayIfSessionQualified() {
        if (isSessionQualified()) {
            this.eligibleForNextPlay = true;
        }
    }

    /**
     * 判断当前心跳是否开启了新一轮观看会话（即离开视频无心跳已超过会话超时时间，如 30 分钟）。
     *
     * @param now 当前时间戳
     * @param sessionTimeout 会话超时时长 (如 30 分钟)
     * @return true 若已超出离开超时时间，应算作新观看会话；false 若仍在原会话期内
     */
    public boolean isNewWatchSession(LocalDateTime now, java.time.Duration sessionTimeout) {
        if (this.lastWatchAt == null) {
            return true;
        }
        return this.lastWatchAt.plus(sessionTimeout).isBefore(now);
    }

    /**
     * 开启新观看会话：重置当前会话的累计时长与事件发送状态，并按上一会话判定继承下一次播放资格。
     *
     * @param previousSessionQualified 上一会话是否达到 30% 消费门槛赋予下一次播放资格
     */
    public void startNewSession(boolean previousSessionQualified) {
        this.eligibleForNextPlay = previousSessionQualified;
        this.sessionWatchedDuration = 0;
        this.sessionPlayEmitted = false;
    }

    /**
     * 超过会话超时时间时，以当前会话资格初始化下一会话状态。
     *
     * <p>逻辑删除记录此前已被授予资格时保留该资格，避免删除与复活之间的状态切换破坏既有防重语义。</p>
     *
     * @param now 当前业务时间
     * @param sessionTimeout 无心跳时长阈值
     * @return true 表示已切换至新会话
     */
    public boolean startNewSessionIfExpired(LocalDateTime now, java.time.Duration sessionTimeout) {
        if (!isNewWatchSession(now, sessionTimeout)) {
            return false;
        }
        boolean previousSessionQualified = isSessionQualified()
                || (this.deleted && this.eligibleForNextPlay);
        startNewSession(previousSessionQualified);
        return true;
    }

    /**
     * 根据已持久化的会话状态计算本次可尝试的有效播放资格。
     *
     * <p>该方法仅给出业务决策，不替代仓储 CAS。并发请求仍必须由数据库以 session_play_emitted、资格与冷却时间作为最终防线。</p>
     *
     * @param now 当前业务时间
     * @param repeatWindow 重复有效播放的冷却周期
     * @param validPlayThreshold 单会话有效播放时长门槛
     * @return 播放资格决策；未达条件时返回 {@link PlayClaimDecision#none()}
     */
    public PlayClaimDecision decidePlayClaim(LocalDateTime now, Duration repeatWindow,
                                             Duration validPlayThreshold) {
        if (now == null || repeatWindow == null || validPlayThreshold == null
                || this.sessionWatchedDuration < validPlayThreshold.toSeconds()
                || this.sessionPlayEmitted) {
            return PlayClaimDecision.none();
        }
        if (this.lastValidPlayAt == null) {
            return PlayClaimDecision.initial();
        }
        LocalDateTime cooldownBoundary = now.minus(repeatWindow);
        if (!this.eligibleForNextPlay || this.lastValidPlayAt.isAfter(cooldownBoundary)) {
            return PlayClaimDecision.none();
        }
        return PlayClaimDecision.repeat(cooldownBoundary);
    }

    /**
     * 判断已更新的观看事实是否需要尝试完播 CAS。
     *
     * <p>仅由领域实体维护位置、视频时长与完播状态的组合语义；数据库仍负责保证多请求下只成功一次。</p>
     *
     * @return true 表示已达到完播阈值且内存状态尚未完播
     */
    public boolean shouldClaimCompletion() {
        return this.videoDuration > 0
                && this.lastPosition >= completionPosition(this.videoDuration)
                && !this.completed;
    }

    /**
     * 标记当前记录为完播状态。
     */
    public void markCompleted() {
        this.completed = true;
    }

    /**
     * 标记本次达成有效播放并更新持久化防重时间戳。
     *
     * @param now 当前时间戳
     */
    public void markValidPlay(LocalDateTime now) {
        this.lastValidPlayAt = now;
    }

    /**
     * 标记当前会话已成功发送播放事件。
     */
    public void markSessionPlayEmitted() {
        this.sessionPlayEmitted = true;
    }

    /**
     * 标记当前会话达到 30% 消费门槛，允许下一次会话在满足条件时触发播放事件。
     */
    public void markEligibleForNextPlay() {
        this.eligibleForNextPlay = true;
    }

    /**
     * 计算指定视频时长对应的会话资格门槛秒数。
     *
     * @param videoDuration 视频总时长（秒）
     * @return 向上取整后的 30% 门槛秒数
     */
    private static int qualifiedSessionDuration(int videoDuration) {
        return (int) Math.ceil(videoDuration * QUALIFIED_THRESHOLD_RATIO);
    }

    /**
     * 计算指定视频时长对应的完播位置阈值。
     *
     * @param videoDuration 视频总时长（秒）
     * @return 向上取整后的 90% 位置阈值
     */
    private static int completionPosition(int videoDuration) {
        return (int) Math.ceil(videoDuration * COMPLETION_THRESHOLD_RATIO);
    }

    /**
     * 有效播放资格的领域决策结果。
     *
     * @param type 可尝试的播放类型
     * @param cooldownBoundary 重复播放 CAS 使用的冷却时间边界；首次或不尝试时为 null
     */
    public record PlayClaimDecision(PlayClaimType type, LocalDateTime cooldownBoundary) {

        /**
         * 构建不应尝试 CAS 的决策。
         *
         * @return 无资格决策
         */
        public static PlayClaimDecision none() {
            return new PlayClaimDecision(PlayClaimType.NONE, null);
        }

        /**
         * 构建首次播放 CAS 决策。
         *
         * @return 首次播放决策
         */
        public static PlayClaimDecision initial() {
            return new PlayClaimDecision(PlayClaimType.INITIAL, null);
        }

        /**
         * 构建重复播放 CAS 决策。
         *
         * @param cooldownBoundary 冷却时间边界
         * @return 重复播放决策
         */
        public static PlayClaimDecision repeat(LocalDateTime cooldownBoundary) {
            return new PlayClaimDecision(PlayClaimType.REPEAT, cooldownBoundary);
        }
    }

    /**
     * 可尝试的有效播放类型。
     */
    public enum PlayClaimType {
        /** 当前会话不应尝试抢占。 */
        NONE,
        /** 应尝试首次有效播放 CAS。 */
        INITIAL,
        /** 应尝试重复有效播放 CAS。 */
        REPEAT
    }

    /**
     * 显式设置下一次播放资格。
     *
     * @param eligible 是否具备资格
     */
    public void setEligibleForNextPlay(boolean eligible) {
        this.eligibleForNextPlay = eligible;
    }

    /**
     * 用户再次起播时刷新活跃时间，并自愈复活已逻辑删除的记录。
     *
     * @param now 当前时间戳
     */
    public void recordPlayStart(LocalDateTime now) {
        this.lastWatchAt = now;
        this.deleted = false;
    }

    /**
     * 逻辑删除观看历史记录（用户主动删除历史记录，但底层持久化保留以维护防重与风控审计）。
     */
    public void markDeleted() {
        this.deleted = true;
    }

    /**
     * 再次播放时自愈复活已逻辑删除的记录：持续累计历史时长，并在新会话内记录当前心跳增量。
     *
     * @param position 当前播放头所在秒数
     * @param deltaDuration 增量时长 (秒)
     * @param videoDuration 视频总时长 (秒)
     */
    public void revive(int position, int deltaDuration, int videoDuration) {
        this.deleted = false;
        this.lastPosition = Math.max(0, position);
        int safeDelta = Math.max(0, deltaDuration);
        this.watchedDuration += safeDelta;
        this.sessionWatchedDuration += safeDelta;
        if (videoDuration > 0) {
            this.videoDuration = videoDuration;
        }
        this.completed = false;
        this.lastWatchAt = LocalDateTime.now();
        markEligibleForNextPlayIfSessionQualified();
    }
}
