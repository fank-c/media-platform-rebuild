package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.config.InteractionWatchProperties;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import com.calles.platform.interaction.domain.model.watch.WatchEventClaim;
import com.calles.platform.interaction.domain.model.watch.WatchEventType;
import com.calles.platform.interaction.domain.model.watch.WatchHistoryEntry;
import com.calles.platform.interaction.domain.model.watch.WatchProgress;
import com.calles.platform.interaction.domain.model.watch.WatchSession;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.VideoSnapshotRepository;
import com.calles.platform.interaction.domain.repository.WatchEventClaimRepository;
import com.calles.platform.interaction.domain.repository.WatchProgressRepository;
import com.calles.platform.interaction.domain.repository.WatchSessionRepository;
import com.calles.platform.interaction.exception.WatchSessionActiveException;
import com.calles.platform.interaction.exception.WatchSessionExpiredException;
import com.calles.platform.interaction.exception.WatchSessionInvalidException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 观看心跳业务规则单元测试。
 *
 * <p>覆盖方案要求的全部判定分支：会话隔离、增量三重上限、30% 与 5 秒门槛、冷却窗口、
 * 每会话一次播放量、每会话一次完播、重复与乱序序号、位置跳跃、无快照降级、删除历史不重置冷却。</p>
 *
 * <p>默认配置下单次上限 15 秒、时间容差 2 秒，因此：无历史参考的首次心跳最多计入 2 秒；
 * 间隔 10 秒的心跳最多计入 12 秒。测试用 {@link #beatAfterGap} 显式模拟心跳间隔。</p>
 *
 * <p>测试使用内存假仓储以便观察连续多次心跳的真实状态流转；计数增量使用 Mockito 打桩，
 * 事件发布使用可记录载荷的子类，便于断言"每会话每类事件只产生一次"。</p>
 */
@ExtendWith(MockitoExtension.class)
class WatchHeartbeatApplicationServiceTest {

    /** 视频公开短码。 */
    private static final String VID = "cv_100";

    /** 用户 ID。 */
    private static final String USER = "user_01";

    private FakeProgressRepository progressRepository;
    private FakeSessionRepository sessionRepository;
    private FakeEventClaimRepository claimRepository;
    private FakeVideoSnapshotRepository snapshotRepository;
    private RecordingEventPublisher eventPublisher;

    @Mock
    private CounterDeltaRepository counterDeltaRepository;

    private WatchHeartbeatApplicationService service;

    @BeforeEach
    void setUp() {
        progressRepository = new FakeProgressRepository();
        sessionRepository = new FakeSessionRepository();
        claimRepository = new FakeEventClaimRepository();
        snapshotRepository = new FakeVideoSnapshotRepository();
        eventPublisher = new RecordingEventPublisher();

        InteractionWatchProperties properties = new InteractionWatchProperties();
        service = new WatchHeartbeatApplicationService(
                progressRepository,
                sessionRepository,
                claimRepository,
                snapshotRepository,
                counterDeltaRepository,
                eventPublisher,
                properties);
    }

    @Test
    @DisplayName("首次起播开启新会话并立即计入播放量，不产生观看行为事件")
    void shouldCreateSessionAndCountViewOnFirstStartPlay() {
        givenSnapshot(100);

        WatchHeartbeatOutcome outcome = startPlay("start_key_001", 5);

        assertThat(outcome.sessionId()).isNotBlank();
        assertThat(outcome.lastPosition()).isEqualTo(5);
        assertThat(outcome.qualificationThreshold()).isEqualTo(30);
        assertThat(outcome.videoDuration()).isEqualTo(100);
        assertThat(outcome.qualifiedThisSession()).isFalse();
        assertThat(outcome.viewCountedThisSession()).isTrue();
        assertThat(outcome.completedThisSession()).isFalse();
        assertThat(outcome.sessionWatchedDuration()).isZero();
        assertThat(outcome.watchedDuration()).isZero();

        // 验证起播增量写入与来源唯一键格式 (watch_session:{sessionId})
        verify(counterDeltaRepository).incrementViewCount(eq(VID), eq("watch_session:" + outcome.sessionId()), eq(1L));
        assertThat(eventPublisher.publishedActions()).isEmpty();
    }

    @Test
    @DisplayName("会话有效观看达到 30% 门槛时发出合格事件，绝不重复计入播放量")
    void shouldEmitQualifiedEventWhenThresholdReachedWithoutTouchingViewCountAgain() {
        givenSnapshot(100);

        WatchHeartbeatOutcome start = startPlay("start_key_002", 0);
        String sessionId = start.sessionId();
        assertThat(start.viewCountedThisSession()).isTrue();

        // 后续心跳逐步累计时长
        WatchHeartbeatOutcome h1 = beat(sessionId, 1L, 10, 15);
        assertThat(h1.viewCountedThisSession()).isTrue();
        assertThat(h1.qualifiedThisSession()).isFalse();

        beatAfterGap(sessionId, 10, 2L, 20, 15);
        beatAfterGap(sessionId, 10, 3L, 30, 15);

        WatchHeartbeatOutcome qualified = beatAfterGap(sessionId, 10, 4L, 40, 15);
        assertThat(qualified.sessionWatchedDuration()).isEqualTo(38);
        assertThat(qualified.qualifiedThisSession()).isTrue();
        assertThat(qualified.viewCountedThisSession()).isTrue();

        // 播放量增量只在起播时写入了恰好 1 次，达标时未额外追加
        verify(counterDeltaRepository, times(1)).incrementViewCount(eq(VID), anyString(), eq(1L));
        // 发出合格观看领域事件
        assertThat(eventPublisher.publishedActions())
                .containsExactly(VideoActionPayload.ACTION_WATCH_VIEW_QUALIFIED);
    }

    @Test
    @DisplayName("短视频门槛取 5 秒最低值：10 秒视频在观看 5 秒后即可发出合格事件")
    void shouldApplyMinimumFiveSecondThresholdForShortVideo() {
        givenSnapshot(10);

        WatchHeartbeatOutcome start = startPlay("start_short", 0);
        String sessionId = start.sessionId();
        assertThat(start.viewCountedThisSession()).isTrue();

        beat(sessionId, 1L, 3, 3);
        WatchHeartbeatOutcome outcome = beatAfterGap(sessionId, 5, 2L, 5, 5);

        assertThat(outcome.qualificationThreshold()).isEqualTo(5);
        assertThat(outcome.qualifiedThisSession()).isTrue();
        assertThat(outcome.viewCountedThisSession()).isTrue();
        assertThat(eventPublisher.publishedActions())
                .containsExactly(VideoActionPayload.ACTION_WATCH_VIEW_QUALIFIED);
    }

    @Test
    @DisplayName("冷却期内的新会话起播不计播放量，但达标后仍能产生合格观看事件，且中途冷却结束不补计")
    void shouldRespectCooldownForStartPlayAndAllowQualifiedEventInCooldownSession() {
        givenSnapshot(100);

        // 步骤 1：首个会话起播正常计入一次播放量
        WatchHeartbeatOutcome firstSession = startPlay("start_first");
        assertThat(firstSession.viewCountedThisSession()).isTrue();
        driveToThreshold(firstSession.sessionId(), 1L);
        assertThat(currentProgress().getLastViewClaimedAt()).isNotNull();
        int claimsAfterFirst = eventPublisher.publishedActions().size();
        assertThat(claimsAfterFirst).isEqualTo(1);

        // 步骤 2：会话超时后开启新会话，处于冷却期内，起播不得计入播放量
        expireActiveSession(40);
        WatchHeartbeatOutcome cooldownSession = startPlay("start_in_cooldown");
        assertThat(cooldownSession.viewCountedThisSession()).isFalse();
        verify(counterDeltaRepository, times(1)).incrementViewCount(anyString(), anyString(), anyLong());

        // 步骤 3：该冷却中的会话达到 30% 时，依然能独立产生合格观看事件
        WatchHeartbeatOutcome inCooldownQualified = driveToThreshold(cooldownSession.sessionId(), 10L);
        assertThat(inCooldownQualified.qualifiedThisSession()).isTrue();
        assertThat(inCooldownQualified.viewCountedThisSession()).isFalse();
        assertThat(eventPublisher.publishedActions()).hasSize(claimsAfterFirst + 1);

        // 步骤 4：在会话进行中即使冷却时间已过，后续心跳绝不中途补计播放量
        currentProgress().markViewClaimed(LocalDateTime.now().minusHours(7));
        WatchHeartbeatOutcome midSessionHeartbeat = beatAfterGap(cooldownSession.sessionId(), 10, 20L, 50, 15);
        assertThat(midSessionHeartbeat.viewCountedThisSession()).isFalse();
        verify(counterDeltaRepository, times(1)).incrementViewCount(anyString(), anyString(), anyLong());

        // 步骤 5：会话超时后再次开启新会话，冷却已过，此时才再次计入播放量
        expireActiveSession(40);
        WatchHeartbeatOutcome thirdSession = startPlay("start_after_cooldown");
        assertThat(thirdSession.viewCountedThisSession()).isTrue();
        verify(counterDeltaRepository, times(2)).incrementViewCount(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("起播幂等键重试返回只读回执，活跃会话存在时拒绝新起播，过期后允许新键")
    void shouldHandleStartPlayIdempotencyAndActiveSessionConflict() {
        givenSnapshot(100);

        // 首次起播
        WatchHeartbeatOutcome first = startPlay("idemp_key_001");
        assertThat(first.sessionId()).isNotBlank();
        assertThat(first.viewCountedThisSession()).isTrue();

        // 同键重试：幂等返回已有会话，不重复增加播放量
        WatchHeartbeatOutcome retry = startPlay("idemp_key_001");
        assertThat(retry.sessionId()).isEqualTo(first.sessionId());
        assertThat(retry.duplicateOrStaleRequest()).isTrue();
        verify(counterDeltaRepository, times(1)).incrementViewCount(anyString(), anyString(), anyLong());

        // 换新键但在旧会话仍活跃时发起起播：抛出 409 WATCH_SESSION_ACTIVE
        assertThatThrownBy(() -> startPlay("idemp_key_002"))
                .isInstanceOf(WatchSessionActiveException.class);

        // 会话超时关闭后
        expireActiveSession(40);

        // 重试已超时的幂等键：抛出 409 WATCH_SESSION_EXPIRED
        assertThatThrownBy(() -> startPlay("idemp_key_001"))
                .isInstanceOf(WatchSessionExpiredException.class);

        // 使用新键起播成功开启新会话
        WatchHeartbeatOutcome newSession = startPlay("idemp_key_003");
        assertThat(newSession.sessionId()).isNotEqualTo(first.sessionId());
    }

    @Test
    @DisplayName("后续心跳上报无效或已过期会话抛出对应 409 异常")
    void shouldRejectSubsequentHeartbeatWithInvalidOrExpiredSession() {
        givenSnapshot(100);

        // 不存在的会话 ID
        assertThatThrownBy(() -> beat("non_existent_sess", 1L, 10, 5))
                .isInstanceOf(WatchSessionInvalidException.class);

        // 属于其他视频的会话 ID
        WatchHeartbeatOutcome valid = startPlay("valid_start_key");
        assertThatThrownBy(() -> service.processHeartbeat("cv_other", USER,
                new WatchHeartbeatCommand(valid.sessionId(), 1L, 10, 5, null)))
                .isInstanceOf(WatchSessionInvalidException.class);

        // 会话超时后上报新心跳
        expireActiveSession(40);
        assertThatThrownBy(() -> beat(valid.sessionId(), 1L, 10, 5))
                .isInstanceOf(WatchSessionExpiredException.class);
    }

    @Test
    @DisplayName("同一会话只产生一次完播事件")
    void shouldEmitCompletionOncePerSession() {
        givenSnapshot(100);

        WatchHeartbeatOutcome start = startPlay("start_completion");
        String s = start.sessionId();
        beat(s, 1L, 10, 15);
        for (long sequence = 2; sequence <= 9; sequence++) {
            beatAfterGap(s, 10, sequence, (int) (sequence * 10), 15);
        }

        WatchHeartbeatOutcome completed = beatAfterGap(s, 10, 10L, 100, 15);
        assertThat(completed.completedThisSession()).isTrue();
        assertThat(completed.sessionWatchedDuration()).isEqualTo(100);
        assertThat(eventPublisher.publishedActions()
                .stream()
                .filter(VideoActionPayload.ACTION_WATCH_COMPLETED::equals)
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("播放位置达到 90% 但有效观看时长不足时判定为未完播")
    void shouldNotCompleteWhenCreditedDurationInsufficient() {
        givenSnapshot(100);

        WatchHeartbeatOutcome start = startPlay("start_insufficient");
        String s = start.sessionId();
        beat(s, 1L, 10, 15);
        WatchHeartbeatOutcome outcome = beatAfterGap(s, 10, 2L, 98, 15);

        assertThat(outcome.lastPosition()).isEqualTo(98);
        assertThat(outcome.sessionWatchedDuration()).isEqualTo(14);
        assertThat(outcome.completedThisSession()).isFalse();
        assertThat(eventPublisher.publishedActions())
                .doesNotContain(VideoActionPayload.ACTION_WATCH_COMPLETED);
    }

    @Test
    @DisplayName("重复序号的心跳幂等返回当前状态，不重复累计时长")
    void shouldNotAccumulateOnDuplicatedSequence() {
        givenSnapshot(100);

        WatchHeartbeatOutcome start = startPlay("start_dup");
        String s = start.sessionId();

        beat(s, 7L, 10, 15);
        WatchHeartbeatOutcome duplicated = beat(s, 7L, 20, 15);

        assertThat(duplicated.duplicateOrStaleRequest()).isTrue();
        assertThat(duplicated.sessionWatchedDuration()).isEqualTo(2);
        assertThat(duplicated.lastPosition()).isEqualTo(10);
    }

    @Test
    @DisplayName("乱序到达的旧序号心跳不回退断点与累计时长")
    void shouldNotRegressOnStaleSequence() {
        givenSnapshot(100);

        WatchHeartbeatOutcome start = startPlay("start_stale");
        String s = start.sessionId();

        beat(s, 5L, 50, 15);
        WatchHeartbeatOutcome stale = beat(s, 3L, 30, 15);

        assertThat(stale.duplicateOrStaleRequest()).isTrue();
        assertThat(stale.lastPosition()).isEqualTo(50);
        assertThat(stale.sessionWatchedDuration()).isEqualTo(2);
    }

    @Test
    @DisplayName("连续有效心跳按服务端时间差与单次上限校验增量")
    void shouldCreditOnlyWithinElapsedTimeForHeartbeats() {
        givenSnapshot(100);

        WatchHeartbeatOutcome start = startPlay("start_credit");
        String s = start.sessionId();

        WatchHeartbeatOutcome first = beat(s, 1L, 5, 5);
        assertThat(first.acceptedSequence()).isEqualTo(1L);
        assertThat(first.duplicateOrStaleRequest()).isFalse();
        assertThat(first.sessionWatchedDuration()).isEqualTo(2);

        // 间隔 5 秒：上限 5 + 2 = 7 秒，客户端上报 5 秒全部认可
        WatchHeartbeatOutcome second = beatAfterGap(s, 5, 2L, 10, 5);
        assertThat(second.sessionWatchedDuration()).isEqualTo(7);

        // 间隔 1 秒：上限 1 + 2 = 3 秒，客户端上报 15 秒被截断为 3 秒
        WatchHeartbeatOutcome third = beatAfterGap(s, 1, 3L, 12, 15);
        assertThat(third.sessionWatchedDuration()).isEqualTo(10);
    }

    @Test
    @DisplayName("播放位置大幅前跳只更新断点，跳跃距离不计入有效观看时长")
    void shouldNotCreditJumpDistance() {
        givenSnapshot(100);

        WatchHeartbeatOutcome start = startPlay("start_jump");
        String s = start.sessionId();
        beat(s, 1L, 10, 15);
        WatchHeartbeatOutcome jumped = beatAfterGap(s, 10, 2L, 90, 15);

        assertThat(jumped.lastPosition()).isEqualTo(90);
        assertThat(jumped.sessionWatchedDuration()).isEqualTo(14);
    }

    @Test
    @DisplayName("缺少视频快照时只保存断点，不产生播放量、合格事件与完播")
    void shouldOnlyPersistProgressWithoutSnapshot() {
        WatchHeartbeatOutcome outcome = startPlay("no_snapshot_key", 60);

        assertThat(outcome.videoDuration()).isZero();
        assertThat(outcome.qualificationThreshold()).isZero();
        assertThat(outcome.qualifiedThisSession()).isFalse();
        assertThat(outcome.viewCountedThisSession()).isFalse();
        assertThat(outcome.completedThisSession()).isFalse();
        assertThat(outcome.lastPosition()).isEqualTo(60);
        assertThat(outcome.sessionWatchedDuration()).isZero();
        verify(counterDeltaRepository, never()).incrementViewCount(anyString(), anyString(), anyLong());
        assertThat(eventPublisher.publishedActions()).isEmpty();
    }

    @Test
    @DisplayName("已发布内容即使时长快照为零也允许起播计数，证明播放量计数不依赖时长可用性")
    void shouldCountViewWhenPublishedEvenIfDurationIsZero() {
        snapshotRepository.put(VideoSnapshot.create(VID, 0, 1, "evt_zero_dur", VideoSnapshot.STATUS_PUBLISHED, LocalDateTime.now()));

        WatchHeartbeatOutcome outcome = startPlay("zero_dur_published_key", 0);

        assertThat(outcome.videoDuration()).isZero();
        assertThat(outcome.qualificationThreshold()).isZero();
        assertThat(outcome.viewCountedThisSession()).isTrue();
        verify(counterDeltaRepository).incrementViewCount(eq(VID), eq("watch_session:" + outcome.sessionId()), eq(1L));
    }

    @Test
    @DisplayName("删除历史只隐藏展示，不重置播放量冷却状态")
    void shouldNotResetCooldownWhenHistoryHidden() {
        givenSnapshot(100);

        WatchHeartbeatOutcome s1 = startPlay("start_hide_1");
        driveToThreshold(s1.sessionId(), 1L);
        LocalDateTime claimedAt = currentProgress().getLastViewClaimedAt();
        assertThat(claimedAt).isNotNull();

        progressRepository.hideByUserAndVid(USER, VID);
        assertThat(progressRepository.hiddenKeys()).contains(USER + "|" + VID);

        expireActiveSession(40);
        WatchHeartbeatOutcome s2 = startPlay("start_hide_2");

        assertThat(s2.viewCountedThisSession()).isFalse();
        assertThat(currentProgress().getLastViewClaimedAt()).isEqualTo(claimedAt);
    }

    @Test
    @DisplayName("视频编码或用户 ID 为空时拒绝处理")
    void shouldRejectBlankIdentifiers() {
        assertThatThrownBy(() -> service.processHeartbeat(" ", USER,
                new WatchHeartbeatCommand(null, 0L, 0, 0, "k")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.processHeartbeat(VID, null,
                new WatchHeartbeatCommand(null, 0L, 0, 0, "k")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 发起一次起播请求。
     *
     * @param startRequestKey 起播请求幂等键
     * @param position 起播断点位置 (秒)
     * @return 心跳结果
     */
    private WatchHeartbeatOutcome startPlay(String startRequestKey, int position) {
        return service.processHeartbeat(VID, USER,
                new WatchHeartbeatCommand(null, 0L, position, 0, startRequestKey));
    }

    /**
     * 发起一次起播请求（默认位置为 0）。
     *
     * @param startRequestKey 起播请求幂等键
     * @return 心跳结果
     */
    private WatchHeartbeatOutcome startPlay(String startRequestKey) {
        return startPlay(startRequestKey, 0);
    }

    /**
     * 执行一次后续心跳，不模拟心跳间隔。
     *
     * @param sessionId 客户端回传会话 ID
     * @param sequence 心跳序号
     * @param position 播放位置 (秒)
     * @param delta 客户端上报增量 (秒)
     * @return 心跳结果
     */
    private WatchHeartbeatOutcome beat(String sessionId, Long sequence, int position, int delta) {
        return service.processHeartbeat(VID, USER,
                new WatchHeartbeatCommand(sessionId, sequence, position, delta, null));
    }

    /**
     * 模拟"距上次心跳已过去 gapSeconds 秒"后再执行一次后续心跳。
     *
     * @param sessionId 客户端回传会话 ID
     * @param gapSeconds 距上次心跳的秒数
     * @param sequence 心跳序号
     * @param position 播放位置 (秒)
     * @param delta 客户端上报增量 (秒)
     * @return 心跳结果
     */
    private WatchHeartbeatOutcome beatAfterGap(String sessionId, long gapSeconds, Long sequence, int position, int delta) {
        sessionRepository.all().stream()
                .filter(session -> session.getClosedAt() == null)
                .forEach(session -> session.recordHeartbeat(0, session.getLastPosition(), null,
                        LocalDateTime.now().minusSeconds(gapSeconds)));
        return beat(sessionId, sequence, position, delta);
    }

    /**
     * 连续上报到本会话达到合格门槛（100 秒视频门槛为 30 秒）。
     *
     * @param sessionId 客户端回传会话 ID
     * @param firstSequence 起始序号
     * @return 最后一次心跳结果
     */
    private WatchHeartbeatOutcome driveToThreshold(String sessionId, long firstSequence) {
        beat(sessionId, firstSequence, 10, 15);
        beatAfterGap(sessionId, 10, firstSequence + 1, 20, 15);
        beatAfterGap(sessionId, 10, firstSequence + 2, 30, 15);
        return beatAfterGap(sessionId, 10, firstSequence + 3, 40, 15);
    }

    /**
     * 把当前活跃会话的最后心跳时间回拨，使下一次心跳触发会话切换。
     *
     * @param minutesAgo 回拨分钟数
     */
    private void expireActiveSession(int minutesAgo) {
        sessionRepository.all().stream()
                .filter(session -> session.getClosedAt() == null)
                .forEach(session -> session.recordHeartbeat(0, session.getLastPosition(), null,
                        LocalDateTime.now().minusMinutes(minutesAgo)));
    }

    /**
     * 写入可用的视频时长快照。
     *
     * @param duration 视频时长 (秒)
     */
    private void givenSnapshot(int duration) {
        snapshotRepository.put(VideoSnapshot.create(VID, duration, 1, "evt_meta_1", "PUBLISHED",
                LocalDateTime.now()));
    }

    /**
     * 读取当前用户在该视频上的进度。
     *
     * @return 观看进度实体
     */
    private WatchProgress currentProgress() {
        return progressRepository.findByUserAndVid(USER, VID).orElseThrow();
    }

    /**
     * 可记录事件载荷的发布器替身，避免测试依赖发件箱持久化细节。
     */
    private static final class RecordingEventPublisher extends InteractionEventPublisher {

        private final List<VideoActionPayload> published = new ArrayList<>();

        /**
         * 构造替身，不注入真实依赖。
         */
        private RecordingEventPublisher() {
            super(null, null, null, null, null);
        }

        @Override
        public String publishVideoAction(VideoActionPayload payload) {
            published.add(payload);
            return "evt_" + published.size();
        }

        /**
         * 收集已发布动作类型。
         *
         * @return 动作类型列表
         */
        private List<String> publishedActions() {
            return published.stream().map(VideoActionPayload::action).toList();
        }
    }

    /**
     * 内存观看进度仓储。
     */
    private static final class FakeProgressRepository implements WatchProgressRepository {

        private final Map<String, WatchProgress> store = new LinkedHashMap<>();

        private final Set<String> hiddenKeys = new LinkedHashSet<>();

        private String key(String userId, String vid) {
            return userId + "|" + vid;
        }

        /**
         * 返回被隐藏展示的记录键。
         *
         * @return 隐藏记录键集合
         */
        private Set<String> hiddenKeys() {
            return hiddenKeys;
        }

        @Override
        public Optional<WatchProgress> lockByUserAndVid(String userId, String vid) {
            return Optional.ofNullable(store.get(key(userId, vid)));
        }

        @Override
        public Optional<WatchProgress> findByUserAndVid(String userId, String vid) {
            return Optional.ofNullable(store.get(key(userId, vid)));
        }

        @Override
        public void insert(WatchProgress progress) {
            store.put(key(progress.getUserId(), progress.getVid()), progress);
        }

        @Override
        public void updateHeartbeat(WatchProgress progress) {
            store.put(key(progress.getUserId(), progress.getVid()), progress);
        }

        @Override
        public void markViewClaimed(String id, LocalDateTime now) {
            store.values().stream()
                    .filter(progress -> progress.getId().equals(id))
                    .forEach(progress -> progress.markViewClaimed(now));
        }

        @Override
        public List<WatchHistoryEntry> findVisibleHistoryPage(String userId, int offset, int limit) {
            return List.of();
        }

        @Override
        public int hideByUserAndVid(String userId, String vid) {
            return hiddenKeys.add(key(userId, vid)) ? 1 : 0;
        }

        @Override
        public int hideAllByUserId(String userId) {
            return 0;
        }

        @Override
        public int detachStaleActiveSessions(LocalDateTime threshold, int limit) {
            return 0;
        }

        @Override
        public int deleteHiddenBefore(LocalDateTime threshold, int limit) {
            return 0;
        }
    }

    /**
     * 内存观看会话仓储。
     */
    private static final class FakeSessionRepository implements WatchSessionRepository {

        private final Map<String, WatchSession> store = new LinkedHashMap<>();

        @Override
        public Optional<WatchSession> findById(String sessionId) {
            return Optional.ofNullable(store.get(sessionId));
        }

        @Override
        public Optional<WatchSession> findByStartRequestKey(String userId, String vid, String startRequestKey) {
            if (userId == null || vid == null || startRequestKey == null) {
                return Optional.empty();
            }
            return store.values().stream()
                    .filter(s -> userId.equals(s.getUserId())
                            && vid.equals(s.getVid())
                            && startRequestKey.equals(s.getStartRequestKey()))
                    .findFirst();
        }

        @Override
        public void insert(WatchSession session) {
            store.put(session.getSessionId(), session);
        }

        @Override
        public void updateHeartbeat(WatchSession session) {
            store.put(session.getSessionId(), session);
        }

        @Override
        public int close(String sessionId, LocalDateTime now) {
            WatchSession session = store.get(sessionId);
            if (session == null) {
                return 0;
            }
            session.close(now);
            return 1;
        }

        @Override
        public int deleteStaleBefore(LocalDateTime threshold, int limit) {
            return 0;
        }

        /**
         * 返回当前全部会话，供测试模拟心跳间隔与会话超时。
         *
         * @return 会话集合
         */
        private Collection<WatchSession> all() {
            return store.values();
        }
    }

    /**
     * 内存观看事件凭据仓储，严格按唯一键语义判定重复。
     */
    private static final class FakeEventClaimRepository implements WatchEventClaimRepository {

        private final Set<String> claimedKeys = new LinkedHashSet<>();

        private String key(WatchEventClaim claim) {
            return claim.getUserId() + "|" + claim.getVid() + "|" + claim.getSessionId()
                    + "|" + claim.getEventType().name();
        }

        @Override
        public boolean tryClaim(WatchEventClaim claim) {
            return claimedKeys.add(key(claim));
        }

        @Override
        public void attachOutboxEventId(String id, String outboxEventId) {
            // 测试不校验 Outbox 事件 ID 回填
        }

        @Override
        public int deleteBefore(LocalDateTime threshold, int limit) {
            return 0;
        }

        @Override
        public Set<WatchEventType> findClaimedTypes(String userId, String vid, String sessionId) {
            Set<WatchEventType> types = new LinkedHashSet<>();
            for (String claimedKey : claimedKeys) {
                String[] parts = claimedKey.split("\\|");
                if (parts[0].equals(userId) && parts[1].equals(vid) && parts[2].equals(sessionId)) {
                    types.add(WatchEventType.valueOf(parts[3]));
                }
            }
            return types;
        }

        @Override
        public Set<String> findCompletedVids(String userId, Collection<String> vids) {
            return Set.of();
        }
    }

    /**
     * 内存视频元数据快照仓储。
     */
    private static final class FakeVideoSnapshotRepository implements VideoSnapshotRepository {

        private final Map<String, VideoSnapshot> store = new HashMap<>();

        /**
         * 写入一条快照。
         *
         * @param snapshot 快照实体
         */
        private void put(VideoSnapshot snapshot) {
            store.put(snapshot.getVid(), snapshot);
        }

        @Override
        public Optional<VideoSnapshot> findByVid(String vid) {
            return Optional.ofNullable(store.get(vid));
        }

        @Override
        public boolean existsBySourceEventId(String sourceEventId) {
            return store.values().stream()
                    .anyMatch(snapshot -> snapshot.getSourceEventId().equals(sourceEventId));
        }

        @Override
        public boolean saveIfNewerOrSameVersion(VideoSnapshot snapshot) {
            store.put(snapshot.getVid(), snapshot);
            return true;
        }
    }
}
