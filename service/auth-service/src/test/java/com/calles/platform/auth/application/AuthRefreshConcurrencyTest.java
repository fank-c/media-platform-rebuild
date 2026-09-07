package com.calles.platform.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.calles.platform.auth.application.outbox.AuthOutboxDispatchNotifier;
import com.calles.platform.auth.config.AuthProperties;
import com.calles.platform.auth.domain.account.AccountRole;
import com.calles.platform.auth.domain.account.AccountStatus;
import com.calles.platform.auth.domain.account.AuthAccount;
import com.calles.platform.auth.exception.AuthException;
import com.calles.platform.auth.infrastructure.messaging.AccountCreatedEventFactory;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRepository;
import com.calles.platform.auth.infrastructure.persistence.AuthAccountMapper;
import com.calles.platform.auth.infrastructure.security.PasswordService;
import com.calles.platform.auth.infrastructure.security.SessionService;
import com.calles.platform.auth.infrastructure.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 刷新与注销交错的应用层回归测试。
 *
 * <p>该测试不代替真实 Redis Lua 并发验证；它通过精确闩锁固定“begin 已成功、账户查询尚未返回”的窗口，
 * 验证应用编排在 logout 删除 sid 后不会退回到登录建会话路径。</p>
 */
class AuthRefreshConcurrencyTest {

    /** 固定时间让候选 Refresh deadline 可被 Mockito 精确匹配。 */
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    /**
     * 在 refresh 已取得轮换资格、尚未执行 finish 时注销当前会话，finish 失去归属后必须返回 401，
     * 且绝不能调用 createSession 复活 sid。
     */
    @Test
    void logoutBetweenBeginAndFinishCannotRecreateSession() throws Exception {
        AuthAccountMapper accountMapper = mock(AuthAccountMapper.class);
        PasswordService passwordService = mock(PasswordService.class);
        TokenService tokenService = mock(TokenService.class);
        SessionService sessionService = mock(SessionService.class);
        AccountCreatedEventFactory eventFactory = mock(AccountCreatedEventFactory.class);
        AuthOutboxRepository outboxRepository = mock(AuthOutboxRepository.class);
        AuthOutboxDispatchNotifier notifier = mock(AuthOutboxDispatchNotifier.class);
        AuthOperationalMetrics metrics = mock(AuthOperationalMetrics.class);
        AuthService authService = new AuthService(accountMapper, passwordService, tokenService, sessionService,
                properties(), Clock.fixed(NOW, ZoneOffset.UTC), eventFactory, outboxRepository, notifier, metrics);

        SessionService.RotationSnapshot rotation = new SessionService.RotationSnapshot("sid", "account-1",
                AccountRole.USER, "old-hash", NOW.plusSeconds(3600), "rotation-id");
        AuthAccount activeAccount = new AuthAccount("account-1", "demo", "hash", AccountRole.USER,
                AccountStatus.ACTIVE, 0, null, null);
        CountDownLatch accountReadEntered = new CountDownLatch(1);
        CountDownLatch allowAccountRead = new CountDownLatch(1);
        when(sessionService.beginRefreshRotation("old-refresh")).thenReturn(Optional.of(rotation));
        when(accountMapper.selectById("account-1")).thenAnswer(invocation -> {
            accountReadEntered.countDown();
            assertTrue(allowAccountRead.await(5, TimeUnit.SECONDS));
            return activeAccount;
        });
        when(tokenService.newRefreshToken()).thenReturn("new-refresh");
        when(tokenService.issueAccessToken("account-1", AccountRole.USER, "sid"))
                .thenReturn(new TokenService.IssuedAccessToken("access", "new-jti", "sid", NOW.plusSeconds(900)));
        when(tokenService.verifyAccessToken("Bearer current-access"))
                .thenReturn(new TokenService.AccessTokenClaims("account-1", AccountRole.USER, "sid", "current-jti",
                        NOW.plusSeconds(900)));
        // 测试替身表达真实 Lua 的条件结果：logout 删除会话后，finish 无法再确认当前 rotationId。
        when(sessionService.finishRefreshRotation(rotation, "new-refresh", AccountRole.USER, NOW.plusSeconds(3600)))
                .thenReturn(false);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AuthService.AuthTokens> refresh = executor.submit(() -> authService.refresh("old-refresh"));
            assertTrue(accountReadEntered.await(5, TimeUnit.SECONDS));

            // 在 finish 前完成真实应用层 logout 编排，而不是直接伪造 finish 的调用顺序。
            authService.logout("Bearer current-access");
            allowAccountRead.countDown();

            ExecutionException exception = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> refresh.get(5, TimeUnit.SECONDS));
            AuthException cause = assertInstanceOf(AuthException.class, exception.getCause());
            assertEquals(401, cause.getStatus().value());

            InOrder sessionOrder = inOrder(sessionService);
            sessionOrder.verify(sessionService).beginRefreshRotation("old-refresh");
            sessionOrder.verify(sessionService).deleteSession("sid");
            sessionOrder.verify(sessionService).finishRefreshRotation(rotation, "new-refresh", AccountRole.USER,
                    NOW.plusSeconds(3600));
            verify(sessionService, never()).createSession(eq("sid"), any(), any(), any(), any());
        } finally {
            allowAccountRead.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** 创建最小的固定认证配置，仅服务于本测试的应用编排。 */
    private AuthProperties properties() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("01234567890123456789012345678901");
        properties.setAccessTokenTtlSeconds(900);
        properties.setRefreshTokenTtlSeconds(3600);
        return properties;
    }
}
