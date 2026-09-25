package com.calles.platform.interaction.application.watch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 观看心跳本地 MySQL/Redis 集成测试。
 *
 * <p>测试仅在显式设置 {@code WATCH_HEARTBEAT_IT_ENABLED=true} 时执行，避免默认单元测试依赖本地基础设施。
 * 运行时需提供隔离的 MySQL 与 Redis 地址；测试数据统一使用 {@code watch_it_} 前缀，并在每个用例后清理。</p>
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

    /** Redis 视频计数 Hash 键。 */
    private static final String COUNTER_KEY = "int:counter:" + VID;

    /** 被验证的真实心跳应用服务。 */
    @Autowired
    private WatchHeartbeatApplicationService heartbeatService;

    /** 用于检查并清理真实 MySQL 数据的 JDBC 工具。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 用于检查并清理真实 Redis 写后缓存的模板。 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 每个用例前清理仅属于该测试的 MySQL 与 Redis 数据。
     */
    @BeforeEach
    void setUp() {
        clearTestData();
    }

    /**
     * 每个用例后清理仅属于该测试的 MySQL 与 Redis 数据，避免污染共享本地容器。
     */
    @AfterEach
    void tearDown() {
        clearTestData();
    }

    /**
     * 验证真实 MySQL CAS、Outbox 写入和事务提交后的 Redis 播放计数协同。
     */
    @Test
    @DisplayName("真实MySQL与Redis：首次5秒且达到90%时按PLAY和PLAY_COMPLETE落库，并在提交后增加Redis播放量")
    void shouldPersistCasEventsAndIncrementRedisOnlyAfterCommit() {
        heartbeatService.processHeartbeat(VID, USER_ID, 90, 5, 100);

        Map<String, Object> history = jdbcTemplate.queryForMap("""
                SELECT session_watched_duration, session_play_emitted, completed, last_valid_play_at
                FROM interaction_watch_history
                WHERE user_id = ? AND vid = ?
                """, USER_ID, VID);
        assertThat(((Number) history.get("session_watched_duration")).intValue()).isEqualTo(5);
        assertThat(((Number) history.get("session_play_emitted")).intValue()).isEqualTo(1);
        assertThat(((Number) history.get("completed")).intValue()).isEqualTo(1);
        assertThat(history.get("last_valid_play_at")).isNotNull();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM interaction_outbox
                WHERE aggregate_id = ?
                  AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.action')) IN ('PLAY', 'PLAY_COMPLETE')
                """, Integer.class, VID)).isEqualTo(2);
        assertThat(redisTemplate.<Object, Object>opsForHash().get(COUNTER_KEY, "view")).isEqualTo("1");

        // 再次同会话上报不应绕过真实 CAS 重复写 PLAY 或重复增加 Redis 计数。
        heartbeatService.processHeartbeat(VID, USER_ID, 95, 5, 100);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM interaction_outbox
                WHERE aggregate_id = ?
                  AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.action')) IN ('PLAY', 'PLAY_COMPLETE')
                """, Integer.class, VID)).isEqualTo(2);
        assertThat(redisTemplate.<Object, Object>opsForHash().get(COUNTER_KEY, "view")).isEqualTo("1");
    }

    /**
     * 删除当前测试生成的数据库行和 Redis 键，不影响其他本地开发数据。
     */
    private void clearTestData() {
        jdbcTemplate.update("DELETE FROM interaction_outbox WHERE aggregate_id = ?", VID);
        jdbcTemplate.update("DELETE FROM interaction_watch_history WHERE user_id = ? AND vid = ?", USER_ID, VID);
        jdbcTemplate.update("DELETE FROM interaction_video_counter WHERE vid = ?", VID);
        redisTemplate.delete(COUNTER_KEY);
        redisTemplate.opsForSet().remove("int:counter:dirty", VID);
    }
}
