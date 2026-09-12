package com.calles.platform.auth.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.calles.platform.auth.config.AuthProperties;
import com.calles.platform.auth.domain.account.AccountRole;
import com.calles.platform.auth.exception.AuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 真实 Redis Lua 协议测试。仅在显式提供隔离 Redis 地址时执行，避免单元测试误连开发或共享环境：
 * {@code AUTH_TEST_REDIS_HOST} 为必填，端口、数据库和密码可选。测试不打印 refresh 原值或会话内容。
 */
@EnabledIfEnvironmentVariable(named = "AUTH_TEST_REDIS_HOST", matches = ".+")
class SessionServiceRedisIntegrationTest {

    /** 本测试创建的 Redis 连接工厂，结束时必须关闭底层客户端线程。 */
    private LettuceConnectionFactory connectionFactory;
    /** 仅访问本测试随机 sid/hash 相关键的 Redis 模板。 */
    private StringRedisTemplate redisTemplate;
    /** 第二个独立 Redis 客户端，模拟另一服务实例而非同一连接上的并发调用。 */
    private LettuceConnectionFactory secondConnectionFactory;
    /** 被测会话服务，使用真实 Redis 执行 Lua 脚本。 */
    private SessionService sessionService;
    /** 当前用例创建的 sid，用于精确清理，禁止使用 KEYS 扫描。 */
    private final List<String> sessionIds = new ArrayList<>();
    /** 当前用例生成的 Refresh Token，用于精确清理哈希索引。 */
    private final List<String> refreshTokens = new ArrayList<>();

    /** 建立仅供本轮测试使用的 Redis 客户端；地址必须由调用者明确提供。 */
    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                System.getenv("AUTH_TEST_REDIS_HOST"), redisPort());
        configuration.setDatabase(redisDatabase());
        String password = System.getenv("AUTH_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            configuration.setPassword(password);
        }
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("01234567890123456789012345678901");
        properties.setAccessTokenTtlSeconds(900);
        properties.setRefreshTokenTtlSeconds(3600);
        sessionService = new SessionService(redisTemplate, new ObjectMapper().findAndRegisterModules(),
                properties, Clock.systemUTC());
    }

    /** 删除当前用例创建的精确键并销毁连接，不触碰其他测试或环境数据。 */
    @AfterEach
    void tearDown() {
        for (String refreshToken : refreshTokens) {
            redisTemplate.delete("auth:refresh:" + SessionService.hashRefreshToken(refreshToken));
        }
        for (String sessionId : sessionIds) {
            redisTemplate.delete("auth:session:" + sessionId);
        }
        if (secondConnectionFactory != null) {
            secondConnectionFactory.destroy();
        }
        connectionFactory.destroy();
    }

    /** 正常轮换只让新索引可用，session 上的 in-flight 字段在 finish 后必须被移除。 */
    @Test
    void normalRotationConsumesOldIndexAndCreatesOnlyNewIndex() {
        String sid = sid();
        String oldToken = token();
        String newToken = token();
        sessionService.createSession(sid, "account-1", AccountRole.USER, oldToken, deadline(), "device-test", "account-1", 5);

        SessionService.RotationSnapshot rotation = sessionService.beginRefreshRotation(oldToken).orElseThrow();
        assertEquals("inflight", redisTemplate.opsForHash().get("auth:session:" + sid, "rotationState"));
        assertTrue(sessionService.finishRefreshRotation(rotation, newToken, AccountRole.ADMIN, deadline(), "account-1"));

        assertFalse(sessionService.beginRefreshRotation(oldToken).isPresent());
        assertEquals(sid, redisTemplate.opsForValue().get("auth:refresh:" + SessionService.hashRefreshToken(newToken)));
        assertEquals("ADMIN", redisTemplate.opsForHash().get("auth:session:" + sid, "role"));
        assertEquals(null, redisTemplate.opsForHash().get("auth:session:" + sid, "rotationId"));
    }

    /** logout 在 begin 和 finish 之间删除 sid 时，finish 只能返回 false，不能复活会话或新索引。 */
    @Test
    void logoutBetweenBeginAndFinishPreventsSessionResurrection() {
        String sid = sid();
        String oldToken = token();
        String newToken = token();
        sessionService.createSession(sid, "account-1", AccountRole.USER, oldToken, deadline(), "device-test", "account-1", 5);
        SessionService.RotationSnapshot rotation = sessionService.beginRefreshRotation(oldToken).orElseThrow();

        sessionService.deleteSession(sid, "account-1");

        assertFalse(sessionService.finishRefreshRotation(rotation, newToken, AccountRole.USER, deadline(), "account-1"));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey("auth:session:" + sid)));
        assertFalse(Boolean.TRUE.equals(redisTemplate.hasKey("auth:refresh:" + SessionService.hashRefreshToken(newToken))));
    }

    /** 两个独立执行线程同时提交同一 Refresh Token 时，Lua begin 最多只能让一个线程取得快照。 */
    @Test
    void concurrentBeginsHaveExactlyOneWinner() throws Exception {
        String sid = sid();
        String oldToken = token();
        sessionService.createSession(sid, "account-1", AccountRole.USER, oldToken, deadline(), "device-test", "account-1", 5);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        SessionService secondInstance = anotherSessionService();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<SessionService.RotationSnapshot>> first = executor.submit(
                    () -> beginAfter(sessionService, start, ready, oldToken));
            Future<Optional<SessionService.RotationSnapshot>> second = executor.submit(
                    () -> beginAfter(secondInstance, start, ready, oldToken));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            Optional<SessionService.RotationSnapshot> a = first.get(5, TimeUnit.SECONDS);
            Optional<SessionService.RotationSnapshot> b = second.get(5, TimeUnit.SECONDS);
            assertEquals(1, (a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0));
            sessionService.abortRefreshRotation(a.orElseGet(b::orElseThrow), null);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 错误 Redis 类型属于依赖/结构异常，必须映射为 503 而不能将索引当作有效凭据。 */
    @Test
    void wrongRefreshIndexTypeFailsClosed() {
        String token = token();
        redisTemplate.opsForHash().put("auth:refresh:" + SessionService.hashRefreshToken(token), "bad", "value");

        AuthException exception = assertThrows(AuthException.class, () -> sessionService.beginRefreshRotation(token));

        assertEquals(503, exception.getStatus().value());
    }

    /** 完成后的迟到 abort 不得删除新索引；新 Token 仍可开始下一次轮换。 */
    @Test
    void lateAbortCannotDeleteCompletedRotation() {
        String sid = sid();
        String oldToken = token();
        String newToken = token();
        sessionService.createSession(sid, "account-1", AccountRole.USER, oldToken, deadline(), "device-test", "account-1", 5);
        SessionService.RotationSnapshot first = sessionService.beginRefreshRotation(oldToken).orElseThrow();
        assertTrue(sessionService.finishRefreshRotation(first, newToken, AccountRole.USER, deadline(), "account-1"));

        assertFalse(sessionService.abortRefreshRotation(first, newToken));
        Optional<SessionService.RotationSnapshot> next = sessionService.beginRefreshRotation(newToken);
        assertTrue(next.isPresent());
        sessionService.abortRefreshRotation(next.orElseThrow(), null);
    }

    /** 在两个线程均已就绪后统一释放，避免依赖 sleep 猜测竞争时序。 */
    private Optional<SessionService.RotationSnapshot> beginAfter(SessionService instance, CountDownLatch start,
                                                                  CountDownLatch ready, String refreshToken)
            throws InterruptedException {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return instance.beginRefreshRotation(refreshToken);
    }

    /** 创建第二个独立 Lettuce 连接和 SessionService，模拟多实例竞争同一 Redis 键。 */
    private SessionService anotherSessionService() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                System.getenv("AUTH_TEST_REDIS_HOST"), redisPort());
        configuration.setDatabase(redisDatabase());
        String password = System.getenv("AUTH_TEST_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            configuration.setPassword(password);
        }
        secondConnectionFactory = new LettuceConnectionFactory(configuration);
        secondConnectionFactory.afterPropertiesSet();
        StringRedisTemplate secondTemplate = new StringRedisTemplate(secondConnectionFactory);
        secondTemplate.afterPropertiesSet();
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("01234567890123456789012345678901");
        properties.setAccessTokenTtlSeconds(900);
        properties.setRefreshTokenTtlSeconds(3600);
        return new SessionService(secondTemplate, new ObjectMapper().findAndRegisterModules(), properties,
                Clock.systemUTC());
    }

    /** 生成当前用例专属 sid，并登记到精确清理列表。 */
    private String sid() {
        String sid = "test-" + UUID.randomUUID();
        sessionIds.add(sid);
        return sid;
    }

    /** 生成高熵测试 Token 并登记到精确清理列表。 */
    private String token() {
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        refreshTokens.add(token);
        return token;
    }

    /** 使用实际系统时间生成短期且足够完成本地测试的绝对 deadline。 */
    private Instant deadline() {
        return Instant.now().plusSeconds(60);
    }

    /** 解析显式测试端口，未配置时使用 Redis 默认端口。 */
    private int redisPort() {
        return parseEnvironmentInt("AUTH_TEST_REDIS_PORT", 6379);
    }

    /** 解析显式测试数据库，默认 15 仅是隔离建议，调用者仍须确认该 Redis DB 归属。 */
    private int redisDatabase() {
        return parseEnvironmentInt("AUTH_TEST_REDIS_DATABASE", 15);
    }

    /** 读取一个受控整数环境变量；非法值应让测试配置失败而非悄悄连向其他 DB。 */
    private int parseEnvironmentInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }
}
