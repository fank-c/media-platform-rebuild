package com.calles.platform.interaction.application.share;

import static org.assertj.core.api.Assertions.assertThat;

import com.calles.platform.interaction.application.query.InteractionQueryApplicationService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 视频分享并发幂等本地 MySQL 集成测试。
 *
 * <p>测试仅在显式设置 {@code INTERACTION_SHARE_IT_ENABLED=true} 时执行，避免默认单测依赖外部基础设施。
 * 运行时依赖真实 MySQL（默认 REPEATABLE READ 隔离级别）。</p>
 *
 * <p>验证目标：双连接并发请求使用相同 (userId, idempotencyKey) 时，
 * 输掉唯一键竞争的事务能通过当前读 (FOR UPDATE) 穿透 MVCC 快照读限制，
 * 正常识别幂等命中而不抛出 500；最终表中只落 1 条分享记录与 1 条增量流水。</p>
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
@EnabledIfEnvironmentVariable(named = "INTERACTION_SHARE_IT_ENABLED", matches = "true")
class InteractionShareLocalInfrastructureIntegrationTest {

    private static final String USER_ID = "share_it_user_1";
    private static final String VID = "share_it_vid_1";
    private static final String IDEM_KEY = "share_it_key_1";

    @Autowired
    private InteractionQueryApplicationService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM interaction_share_record WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM interaction_counter_delta WHERE source_id LIKE ?", "share:" + USER_ID + ":%");
        jdbcTemplate.update("DELETE FROM interaction_outbox WHERE aggregate_id = ?", USER_ID + ":" + VID);
    }

    @Test
    @DisplayName("双连接并发：相同(userId, key)交错执行时，当前读穿透快照且双方均成功，数据严格唯一")
    void shouldHandleConcurrentSharesGracefullyUnderRepeatableRead() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<?> f1 = pool.submit(() -> {
            try {
                startLatch.await();
                queryService.recordShare(VID, USER_ID, IDEM_KEY);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Future<?> f2 = pool.submit(() -> {
            try {
                startLatch.await();
                queryService.recordShare(VID, USER_ID, IDEM_KEY);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 同时放行两个线程
        startLatch.countDown();

        // 两个线程都应该正常结束，不抛出异常 (特别是输掉的事务不抛 DuplicateKeyException 或 500)
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // 数据库验证：分享记录只有 1 条
        Integer shareRecordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_share_record WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, USER_ID, IDEM_KEY);
        assertThat(shareRecordCount).isEqualTo(1);

        // 计数增量表只有 1 条增量记录
        Integer deltaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_counter_delta WHERE source_id = ?",
                Integer.class, "share:" + USER_ID + ":" + IDEM_KEY);
        assertThat(deltaCount).isEqualTo(1);
    }
}
