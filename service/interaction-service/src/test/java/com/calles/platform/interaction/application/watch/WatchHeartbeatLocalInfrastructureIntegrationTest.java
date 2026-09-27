package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;

import com.calles.platform.interaction.application.video.VideoMetadataApplicationService;
import com.calles.platform.interaction.interfaces.messaging.event.VideoMetadataMessage;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 观看心跳本地 MySQL 集成测试。
 *
 * <p>测试仅在显式设置 {@code WATCH_HEARTBEAT_IT_ENABLED=true} 时执行，避免默认单元测试依赖本地基础设施。
 * 运行时需提供隔离的 MySQL 地址；测试数据统一使用 {@code watch_it_} 前缀，并在每个用例前后清理。</p>
 *
 * <p>验证真实数据库上的四件事：元数据事件幂等建快照、播放量凭据唯一键防重、
 * 完播凭据唯一键防重、以及重复序号不产生任何新写入。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.task.scheduling.enabled=false",
        "interaction.outbox.dispatch-enabled=false",
        "interaction.outbox.fast-dispatch-enabled=false",
        "interaction.outbox.poll-interval=5000"
})
@EnabledIfEnvironmentVariable(named = "WATCH_HEARTBEAT_IT_ENABLED", matches = "true")
class WatchHeartbeatLocalInfrastructureIntegrationTest {

    /** 本集成测试使用的用户 ID。 */
    private static final String USER_ID = "watch_it_user";

    /** 本集成测试使用的视频短码。 */
    private static final String VID = "watch_it_vid";

    /** 本集成测试使用的视频时长 (秒)，门槛为 30 秒、完播需 90 秒。 */
    private static final int DURATION = 100;

    /** 模拟两次心跳之间经过的秒数，使单次增量上限 15 秒生效。 */
    private static final int HEARTBEAT_GAP_SECONDS = 30;

    /** 被验证的真实心跳应用服务。 */
    @Autowired
    private WatchHeartbeatApplicationService heartbeatService;

    /** 被验证的真实视频元数据消费应用服务。 */
    @Autowired
    private VideoMetadataApplicationService videoMetadataApplicationService;

    /** 用于检查并清理真实 MySQL 数据的 JDBC 工具。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 观看会话持久化 Mapper。 */
    @Autowired
    private com.calles.platform.interaction.infrastructure.persistence.mapper.WatchSessionMapper sessionMapper;

    /** 观看进度持久化 Mapper。 */
    @Autowired
    private com.calles.platform.interaction.infrastructure.persistence.mapper.WatchProgressMapper progressMapper;

    /**
     * 每个用例前清理仅属于该测试的数据。
     */
    @BeforeEach
    void setUp() {
        clearTestData();
    }

    /**
     * 每个用例后清理仅属于该测试的数据，避免污染共享本地容器。
     */
    @AfterEach
    void tearDown() {
        clearTestData();
    }

    @Test
    @DisplayName("真实MySQL：元数据事件幂等建快照，起播计一次播放量，门槛发出合格事件，完播一次，重复序号不产生新写入")
    void shouldPersistWatchStateAndClaimsIdempotently() {
        // 步骤 1：元数据事件建立本地时长快照，重复投递必须幂等跳过
        VideoMetadataMessage metadata = metadataMessage("evt_meta_it", DURATION, 1);
        assertThat(videoMetadataApplicationService.apply(metadata))
                .isEqualTo(VideoMetadataApplicationService.SnapshotApplyResult.APPLIED);
        assertThat(videoMetadataApplicationService.apply(metadata))
                .isEqualTo(VideoMetadataApplicationService.SnapshotApplyResult.SKIPPED_DUPLICATE);
        assertThat(snapshotDuration()).isEqualTo(DURATION);

        // 步骤 2：起播开启新会话，已发布且无冷却立即计入播放量，此时未达合格时长门槛
        WatchHeartbeatOutcome first = heartbeatService.processHeartbeat(
                VID, USER_ID, new WatchHeartbeatCommand(null, 0L, 5, 0, "it_start_key_1"));
        assertThat(first.qualificationThreshold()).isEqualTo(30);
        assertThat(first.qualifiedThisSession()).isFalse();
        assertThat(first.viewCountedThisSession()).isTrue();
        assertThat(claimCount(null)).isZero();
        assertThat(counterDeltaCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_view_claimed_at FROM interaction_watch_progress WHERE user_id = ? AND vid = ?",
                java.sql.Timestamp.class, USER_ID, VID)).isNotNull();

        // 步骤 3：模拟心跳间隔后累计到 32 秒，达到门槛并抢占合格观看凭据，播放量不重复增加
        backdateActiveSession();
        WatchHeartbeatOutcome second = heartbeatService.processHeartbeat(
                VID, USER_ID, new WatchHeartbeatCommand(first.sessionId(), 1L, 20, 15));
        assertThat(second.sessionWatchedDuration()).isEqualTo(17);

        backdateActiveSession();
        WatchHeartbeatOutcome qualified = heartbeatService.processHeartbeat(
                VID, USER_ID, new WatchHeartbeatCommand(first.sessionId(), 2L, 35, 15));
        assertThat(qualified.sessionWatchedDuration()).isEqualTo(32);
        assertThat(qualified.qualifiedThisSession()).isTrue();
        assertThat(qualified.viewCountedThisSession()).isTrue();

        assertThat(claimCount("WATCH_VIEW_QUALIFIED")).isEqualTo(1);
        assertThat(counterDeltaCount()).isEqualTo(1);
        assertThat(outboxActions()).containsExactly("WATCH_VIEW_QUALIFIED");

        // 步骤 4：继续累计到 92 秒并到达视频末尾，抢占完播凭据且不增加播放量
        long sequence = 3L;
        for (int i = 0; i < 4; i++) {
            backdateActiveSession();
            WatchHeartbeatOutcome outcome = heartbeatService.processHeartbeat(
                    VID, USER_ID, new WatchHeartbeatCommand(first.sessionId(), sequence++, 100, 15));
            if (i == 3) {
                assertThat(outcome.completedThisSession()).isTrue();
            }
        }
        assertThat(claimCount("WATCH_COMPLETED")).isEqualTo(1);
        assertThat(counterDeltaCount()).isEqualTo(1);
        assertThat(outboxActions()).containsExactlyInAnyOrder("WATCH_VIEW_QUALIFIED", "WATCH_COMPLETED");

        Map<String, Object> session = jdbcTemplate.queryForMap("""
                SELECT credited_duration, last_sequence, qualified, closed_at
                FROM interaction_watch_session
                WHERE session_id = ?
                """, first.sessionId());
        assertThat(((Number) session.get("credited_duration")).intValue()).isEqualTo(92);
        assertThat(((Number) session.get("last_sequence")).longValue()).isEqualTo(6L);
        assertThat(((Number) session.get("qualified")).intValue()).isEqualTo(1);
        assertThat(session.get("closed_at")).isNull();

        Map<String, Object> progress = jdbcTemplate.queryForMap("""
                SELECT last_position, watched_duration, deleted
                FROM interaction_watch_progress
                WHERE user_id = ? AND vid = ?
                """, USER_ID, VID);
        assertThat(((Number) progress.get("last_position")).intValue()).isEqualTo(100);
        assertThat(((Number) progress.get("watched_duration")).intValue()).isEqualTo(92);
        assertThat(((Number) progress.get("deleted")).intValue()).isZero();

        // 步骤 5：重复序号属于幂等重放，不得新增凭据、事件或计数增量
        WatchHeartbeatOutcome replay = heartbeatService.processHeartbeat(
                VID, USER_ID, new WatchHeartbeatCommand(first.sessionId(), 6L, 100, 15));
        assertThat(replay.duplicateOrStaleRequest()).isTrue();
        assertThat(claimCount(null)).isEqualTo(2);
        assertThat(counterDeltaCount()).isEqualTo(1);
        assertThat(outboxActions()).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT credited_duration FROM interaction_watch_session WHERE session_id = ?
                """, Integer.class, first.sessionId())).isEqualTo(92);
    }

    @Test
    @DisplayName("真实MySQL：没有时长快照时只保存断点，不产生凭据与事件")
    void shouldOnlyPersistProgressWhenSnapshotMissing() {
        // 起播但快照缺失：内容未发布，不计数播放量，仅保留断点
        WatchHeartbeatOutcome outcome = heartbeatService.processHeartbeat(
                VID, USER_ID, new WatchHeartbeatCommand(null, 0L, 60, 0, "it_start_key_missing_snap"));

        assertThat(outcome.videoDuration()).isZero();
        assertThat(outcome.qualificationThreshold()).isZero();
        assertThat(outcome.viewCountedThisSession()).isFalse();
        assertThat(outcome.completedThisSession()).isFalse();
        assertThat(outcome.lastPosition()).isEqualTo(60);
        assertThat(outcome.acceptedSequence()).isNull();

        assertThat(claimCount(null)).isZero();
        assertThat(counterDeltaCount()).isZero();
        assertThat(outboxActions()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT last_position FROM interaction_watch_progress WHERE user_id = ? AND vid = ?
                """, Integer.class, USER_ID, VID)).isEqualTo(60);
    }

    @Test
    @DisplayName("真实MySQL：并发或乱序元数据事件在行锁与版本条件保护下，高版本绝不被低版本覆盖")
    void shouldPreventSnapshotDowngradeUnderConcurrentEvents() throws Exception {
        // 先建立初始版本 1 (时长 100 秒)
        VideoMetadataMessage v1 = metadataMessage("evt_meta_it_1", 100, 1);
        assertThat(videoMetadataApplicationService.apply(v1))
                .isEqualTo(VideoMetadataApplicationService.SnapshotApplyResult.APPLIED);

        // 模拟多线程并发投递版本 2 (时长 200 秒) 与版本 3 (时长 300 秒)
        VideoMetadataMessage v2 = metadataMessage("evt_meta_it_2", 200, 2);
        VideoMetadataMessage v3 = metadataMessage("evt_meta_it_3", 300, 3);

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<VideoMetadataApplicationService.SnapshotApplyResult> f2 = pool.submit(() -> {
            latch.await();
            return videoMetadataApplicationService.apply(v2);
        });
        java.util.concurrent.Future<VideoMetadataApplicationService.SnapshotApplyResult> f3 = pool.submit(() -> {
            latch.await();
            return videoMetadataApplicationService.apply(v3);
        });

        latch.countDown();
        f2.get(5, java.util.concurrent.TimeUnit.SECONDS);
        f3.get(5, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        // 数据库无论交错时序如何，最终快照必须稳定在版本 3，时长为 300 秒
        Map<String, Object> snapshot = jdbcTemplate.queryForMap(
                "SELECT duration, metadata_version FROM interaction_video_snapshot WHERE vid = ?", VID);
        assertThat(((Number) snapshot.get("metadata_version")).intValue()).isEqualTo(3);
        assertThat(((Number) snapshot.get("duration")).intValue()).isEqualTo(300);

        // 此时再显式到达低版本 2 事件，必定被原子更新/版本前置校验拦截跳过
        assertThat(videoMetadataApplicationService.apply(v2))
                .isEqualTo(VideoMetadataApplicationService.SnapshotApplyResult.SKIPPED_STALE_VERSION);
        assertThat(snapshotDuration()).isEqualTo(300);
    }

    @Test
    @DisplayName("真实MySQL：超期数据量超过批大小时，NOT EXISTS 确保未解绑的会话绝不被提前删除留下悬空引用")
    void shouldProtectSessionsStillReferencedByProgressWhenCleaningRetention() {
        LocalDateTime oldTime = LocalDateTime.now().minusDays(40);
        String s1 = "sess_it_ret_1";
        String s2 = "sess_it_ret_2";
        String s3 = "sess_it_ret_3";

        // 构造 3 个超期会话: s1, s2, s3
        jdbcTemplate.update("""
                INSERT INTO interaction_watch_session
                    (session_id, user_id, vid, duration_snapshot, qualification_threshold,
                     credited_duration, last_sequence, last_position, qualified, started_at, last_heartbeat_at)
                VALUES
                    (?, ?, ?, 100, 30, 10, 1, 10, 0, ?, ?),
                    (?, ?, 'watch_it_vid_2', 100, 30, 10, 1, 10, 0, ?, ?),
                    (?, ?, 'watch_it_vid_3', 100, 30, 10, 1, 10, 0, ?, ?)
                """,
                s1, USER_ID, VID, oldTime, oldTime,
                s2, USER_ID, oldTime, oldTime,
                s3, USER_ID, oldTime, oldTime);

        // 构造 3 条对应的 progress 记录，分别引用 s1, s2, s3
        jdbcTemplate.update("""
                INSERT INTO interaction_watch_progress
                    (id, user_id, vid, active_session_id, last_position, watched_duration, first_watch_at, last_watch_at, deleted)
                VALUES
                    ('prog_it_1', ?, ?, ?, 10, 10, ?, ?, 0),
                    ('prog_it_2', ?, 'watch_it_vid_2', ?, 10, 10, ?, ?, 0),
                    ('prog_it_3', ?, 'watch_it_vid_3', ?, 10, 10, ?, ?, 0)
                """,
                USER_ID, VID, s1, oldTime, oldTime,
                USER_ID, s2, oldTime, oldTime,
                USER_ID, s3, oldTime, oldTime);

        LocalDateTime threshold = LocalDateTime.now().minusDays(30);

        // 步骤 1：第一批只解绑 1 个进度记录（模拟批大小小于超期记录数，产生批次错位）
        int detached = progressMapper.detachStaleActiveSessions(threshold, 1);
        assertThat(detached).isEqualTo(1);

        // 步骤 2：尝试删除最多 3 个超期会话
        // 关键断言：由于 NOT EXISTS 保护，未被解绑的 2 个会话绝对不能被删除！影响行数必定为 1！
        int deleted = sessionMapper.deleteStaleBefore(threshold, 3);
        assertThat(deleted).isEqualTo(1);

        // 检查剩余的 2 个未解绑 progress，其引用的 session 必须依然真实存在于会话表中，杜绝悬空引用！
        Integer remainingReferenced = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM interaction_watch_progress p
                JOIN interaction_watch_session s ON s.session_id = p.active_session_id
                WHERE p.user_id = ? AND p.active_session_id IS NOT NULL
                """, Integer.class, USER_ID);
        assertThat(remainingReferenced).isEqualTo(2);

        // 步骤 3：第二轮将剩余的 2 个进度解绑并删除对应会话
        int detachedRound2 = progressMapper.detachStaleActiveSessions(threshold, 2);
        assertThat(detachedRound2).isEqualTo(2);

        int deletedRound2 = sessionMapper.deleteStaleBefore(threshold, 2);
        assertThat(deletedRound2).isEqualTo(2);

        // 全部清理干净，会话表中不再存在旧会话
        Integer totalSessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_watch_session WHERE user_id = ?", Integer.class, USER_ID);
        assertThat(totalSessions).isZero();
    }

    /**
     * 把当前活跃会话的最后心跳时间回拨，使下一次心跳的单次增量上限生效。
     */
    private void backdateActiveSession() {
        jdbcTemplate.update("""
                UPDATE interaction_watch_session
                SET last_heartbeat_at = DATE_SUB(NOW(3), INTERVAL ? SECOND)
                WHERE user_id = ? AND vid = ? AND closed_at IS NULL
                """, HEARTBEAT_GAP_SECONDS, USER_ID, VID);
    }

    /**
     * 构造视频元数据事件消息。
     *
     * @param eventId 事件 ID
     * @param duration 视频时长 (秒)
     * @param metadataVersion 元数据版本
     * @return 元数据消息
     */
    private VideoMetadataMessage metadataMessage(String eventId, int duration, int metadataVersion) {
        return new VideoMetadataMessage(eventId, "content.video.metadata", 1, "trace_it", Instant.now(),
                "video_it", VID, duration, metadataVersion, "PUBLISHED", Instant.now());
    }

    /**
     * 读取当前测试视频的本地快照时长。
     *
     * @return 快照时长 (秒)
     */
    private int snapshotDuration() {
        return jdbcTemplate.queryForObject(
                "SELECT duration FROM interaction_video_snapshot WHERE vid = ?", Integer.class, VID);
    }

    /**
     * 统计事件凭据条数。
     *
     * @param eventType 事件类型，为空时统计全部
     * @return 凭据条数
     */
    private int claimCount(String eventType) {
        if (eventType == null) {
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM interaction_watch_event_claim WHERE user_id = ? AND vid = ?",
                    Integer.class, USER_ID, VID);
        }
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM interaction_watch_event_claim
                WHERE user_id = ? AND vid = ? AND event_type = ?
                """, Integer.class, USER_ID, VID, eventType);
    }

    /**
     * 统计播放量计数增量条数。
     *
     * @return 增量条数
     */
    private int counterDeltaCount() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM interaction_counter_delta
                WHERE vid = ? AND counter_type = 'VIEW' AND source_type = 'WATCH_PLAY'
                """, Integer.class, VID);
    }

    /**
     * 读取本测试视频在 Outbox 中产生的事件动作类型。
     *
     * @return 动作类型列表
     */
    private List<String> outboxActions() {
        return jdbcTemplate.queryForList("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.action')) AS action
                FROM interaction_outbox
                WHERE aggregate_id = ?
                ORDER BY occurred_at ASC
                """, String.class, VID);
    }

    /**
     * 删除当前测试生成的全部数据，不影响其他本地开发数据。
     */
    private void clearTestData() {
        jdbcTemplate.update("DELETE FROM interaction_watch_event_claim WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM interaction_watch_session WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM interaction_watch_progress WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM interaction_video_snapshot WHERE vid = ?", VID);
        jdbcTemplate.update("DELETE FROM interaction_outbox WHERE aggregate_id = ?", VID);
        jdbcTemplate.update("DELETE FROM interaction_counter_delta WHERE vid = ?", VID);
        jdbcTemplate.update("DELETE FROM interaction_video_counter WHERE vid = ?", VID);
    }
}
