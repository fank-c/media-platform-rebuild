package com.calles.platform.interaction.domain.model.watch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 观看时长校验、门槛计算与会话状态纯领域测试。
 *
 * <p>这些规则是播放量与完播判定的全部口径来源，必须脱离数据库与框架单独验证。</p>
 */
class WatchDomainPolicyTest {

    private static final Duration MAX_DELTA = Duration.ofSeconds(15);

    private static final Duration TOLERANCE = Duration.ofSeconds(10);

    private static final Duration MIN_THRESHOLD = Duration.ofSeconds(5);

    @Test
    @DisplayName("增量同时受单次上限与服务端时间差上限约束")
    void shouldCapCreditedDeltaByBothLimits() {
        LocalDateTime now = LocalDateTime.now();

        // 时间差 3 秒 + 容差 10 秒 = 13 秒上限，客户端报 15 秒只认可 13 秒
        int creditedByTime = WatchCreditValidator.resolveCreditedDelta(
                15, now.minusSeconds(3), now, MAX_DELTA, TOLERANCE, 300, 0);
        assertThat(creditedByTime).isEqualTo(13);

        // 时间差 60 秒时，单次上限 15 秒生效
        int creditedBySingleCap = WatchCreditValidator.resolveCreditedDelta(
                60, now.minusSeconds(60), now, MAX_DELTA, TOLERANCE, 300, 0);
        assertThat(creditedBySingleCap).isEqualTo(15);
    }

    @Test
    @DisplayName("非正增量与超过视频时长剩余的增量都不被认可")
    void shouldRejectInvalidOrExcessiveDelta() {
        LocalDateTime now = LocalDateTime.now();
        assertThat(WatchCreditValidator.resolveCreditedDelta(null, now, now, MAX_DELTA, TOLERANCE, 300, 0)).isZero();
        assertThat(WatchCreditValidator.resolveCreditedDelta(-5, now, now, MAX_DELTA, TOLERANCE, 300, 0)).isZero();
        assertThat(WatchCreditValidator.resolveCreditedDelta(0, now, now, MAX_DELTA, TOLERANCE, 300, 0)).isZero();

        // 视频仅 20 秒且已计入 15 秒，剩余 5 秒即为本次上限
        assertThat(WatchCreditValidator.resolveCreditedDelta(
                15, now.minusSeconds(30), now, MAX_DELTA, TOLERANCE, 20, 15)).isEqualTo(5);
        assertThat(WatchCreditValidator.resolveCreditedDelta(
                15, now.minusSeconds(30), now, MAX_DELTA, TOLERANCE, 20, 20)).isZero();
    }

    @Test
    @DisplayName("位置前跳幅度明显超过认可增量时判定为可疑跳跃")
    void shouldDetectSuspiciousForwardJump() {
        assertThat(WatchCreditValidator.isSuspiciousForwardJump(10, 95, 10, TOLERANCE)).isTrue();
        assertThat(WatchCreditValidator.isSuspiciousForwardJump(10, 25, 10, TOLERANCE)).isFalse();
    }

    @Test
    @DisplayName("播放量门槛取最低时长与 30% 时长比例中的较大值")
    void shouldResolveQualificationThreshold() {
        // 10 秒视频：30% 为 3 秒，低于 5 秒最低门槛，取 5 秒
        assertThat(WatchQualificationPolicy.resolveQualificationThreshold(10, MIN_THRESHOLD, 0.30D)).isEqualTo(5);
        // 100 秒视频：30% 为 30 秒，高于最低门槛，取 30 秒
        assertThat(WatchQualificationPolicy.resolveQualificationThreshold(100, MIN_THRESHOLD, 0.30D)).isEqualTo(30);
        // 11 秒视频：30% 为 3.3 秒向上取整为 4 秒，仍低于最低门槛，取 5 秒
        assertThat(WatchQualificationPolicy.resolveQualificationThreshold(11, MIN_THRESHOLD, 0.30D)).isEqualTo(5);
        // 无有效时长快照时门槛为 0，调用方必须据此跳过全部资格判定
        assertThat(WatchQualificationPolicy.resolveQualificationThreshold(0, MIN_THRESHOLD, 0.30D)).isZero();
    }

    @Test
    @DisplayName("无有效门槛时不允许达标")
    void shouldNotQualifyWithoutValidThreshold() {
        assertThat(WatchQualificationPolicy.isQualified(100, 0)).isFalse();
        assertThat(WatchQualificationPolicy.isQualified(30, 30)).isTrue();
        assertThat(WatchQualificationPolicy.isQualified(29, 30)).isFalse();
    }

    @Test
    @DisplayName("完播必须同时满足位置与有效时长双 90% 条件")
    void shouldRequireBothPositionAndDurationForCompletion() {
        assertThat(WatchQualificationPolicy.isCompleted(95, 95, 100, 0.90D)).isTrue();
        assertThat(WatchQualificationPolicy.isCompleted(95, 20, 100, 0.90D)).isFalse();
        assertThat(WatchQualificationPolicy.isCompleted(20, 95, 100, 0.90D)).isFalse();
        assertThat(WatchQualificationPolicy.isCompleted(100, 100, 0, 0.90D)).isFalse();
    }

    @Test
    @DisplayName("冷却窗口未结束时不允许再次计入，结束后允许")
    void shouldCheckCooldownWindow() {
        LocalDateTime now = LocalDateTime.now();
        Duration window = Duration.ofHours(6);

        assertThat(WatchQualificationPolicy.isCooldownElapsed(null, now, window)).isTrue();
        assertThat(WatchQualificationPolicy.isCooldownElapsed(now.minusHours(1), now, window)).isFalse();
        assertThat(WatchQualificationPolicy.isCooldownElapsed(now.minusHours(7), now, window)).isTrue();
    }

    @Test
    @DisplayName("会话超时判定与序号重复/乱序判定")
    void shouldJudgeSessionExpiryAndSequenceOrder() {
        LocalDateTime now = LocalDateTime.now();
        WatchSession session = WatchSession.open("user_01", "cv_1", 100, 30, 0, now);
        assertThat(session.getSessionId()).isNotBlank();
        assertThat(session.isExpired(now.plusMinutes(29), Duration.ofMinutes(30))).isFalse();
        assertThat(session.isExpired(now.plusMinutes(31), Duration.ofMinutes(30))).isTrue();

        session.recordHeartbeat(10, 20, 5L, now);
        assertThat(session.getCreditedDuration()).isEqualTo(10);
        assertThat(session.getLastPosition()).isEqualTo(20);
        assertThat(session.isExactReplay(5L)).isTrue();
        assertThat(session.isReplayOrStale(5L)).isTrue();
        assertThat(session.isReplayOrStale(4L)).isTrue();
        assertThat(session.isReplayOrStale(6L)).isFalse();
        assertThat(session.isReplayOrStale(null)).isFalse();
    }

    @Test
    @DisplayName("会话资格状态只允许由未达标变为达标一次")
    void shouldRefreshQualificationOnce() {
        LocalDateTime now = LocalDateTime.now();
        WatchSession session = WatchSession.open("user_01", "cv_1", 100, 30, 0, now);

        session.recordHeartbeat(29, 29, 1L, now);
        assertThat(session.refreshQualification()).isFalse();
        assertThat(session.isQualified()).isFalse();

        session.recordHeartbeat(1, 30, 2L, now);
        assertThat(session.refreshQualification()).isTrue();
        assertThat(session.isQualified()).isTrue();
        assertThat(session.refreshQualification()).isFalse();
    }
}
