package com.calles.platform.auth.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRecord;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRepository;
import com.calles.platform.auth.infrastructure.persistence.AuthAccountMapper;
import com.calles.platform.auth.infrastructure.security.PasswordService;
import com.calles.platform.auth.infrastructure.security.SessionService;
import com.calles.platform.auth.infrastructure.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 认证用例编排测试。隔离 Mapper、密码、令牌与 Redis 会话依赖，验证刷新失败时不会签发或复活会话。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    /** 固定时间保证会话和令牌到期断言不依赖运行时钟。 */
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Mock
    private AuthAccountMapper accountMapper;
    @Mock
    private PasswordService passwordService;
    @Mock
    private TokenService tokenService;
    @Mock
    private SessionService sessionService;
    @Mock
    private AccountCreatedEventFactory accountCreatedEventFactory;
    @Mock
    private AuthOutboxRepository outboxRepository;
    @Mock
    private AuthOutboxDispatchNotifier outboxDispatchNotifier;
    @Mock
    private AuthOperationalMetrics operationalMetrics;

    private AuthService authService;
    private AuthAccount activeAccount;
    private TokenService.IssuedAccessToken issuedAccessToken;

    /** 为每个用例重建独立 Mock，避免令牌和会话交互跨用例残留。 */
    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("01234567890123456789012345678901");
        properties.setAccessTokenTtlSeconds(900);
        properties.setRefreshTokenTtlSeconds(3600);
        authService = new AuthService(accountMapper, passwordService, tokenService, sessionService,
                properties, Clock.fixed(NOW, ZoneOffset.UTC), accountCreatedEventFactory, outboxRepository,
                outboxDispatchNotifier, operationalMetrics);
        activeAccount = new AuthAccount("account-1", "demo@example.com", "hash", AccountRole.USER,
                AccountStatus.ACTIVE, 0, null, null);
        issuedAccessToken = new TokenService.IssuedAccessToken("access", "jti", "sid",
                NOW.plusSeconds(900));
    }

    /** 正确凭据必须同时建立新 sid 会话并返回新的令牌对。 */
    @Test
    void loginWithCorrectPasswordReturnsTokenPair() {
        when(accountMapper.findByEmail("demo@example.com")).thenReturn(activeAccount);
        when(passwordService.matches("password", "hash")).thenReturn(true);
        when(tokenService.newSessionId()).thenReturn("sid");
        when(tokenService.newRefreshToken()).thenReturn("refresh");
        when(tokenService.issueAccessToken("account-1", AccountRole.USER, "sid"))
                .thenReturn(issuedAccessToken);

        AuthService.AuthTokens result = authService.login(" DEMO@EXAMPLE.COM ", "password", null);

        assertEquals("access", result.accessToken());
        assertEquals("refresh", result.refreshToken());
        assertEquals(AccountRole.USER, result.role());
        verify(sessionService).createSession(eq("sid"), eq("account-1"), eq(AccountRole.USER),
                eq("refresh"), eq(NOW.plusSeconds(3600)), any(), eq("account-1"), anyInt());
    }

    /** 密码校验失败不得创建会话或请求新令牌。 */
    @Test
    void loginWithWrongPasswordFails() {
        when(accountMapper.findByEmail("demo@example.com")).thenReturn(activeAccount);
        when(passwordService.matches("wrong", "hash")).thenReturn(false);

        assertThrows(AuthException.class, () -> authService.login("demo@example.com", "wrong", null));
        verify(tokenService, never()).newSessionId();
    }

    /** 禁用账户在密码比对前即被拒绝，避免无意义的密码工作。 */
    @Test
    void disabledAccountCannotLogin() {
        activeAccount.setStatus(AccountStatus.DISABLED);
        when(accountMapper.findByEmail("demo@example.com")).thenReturn(activeAccount);

        AuthException exception = assertThrows(AuthException.class,
                () -> authService.login("demo@example.com", "password", null));

        assertEquals(403, exception.getStatus().value());
        verify(passwordService, never()).matches(any(), any());
    }

    /** 注册成功必须在返回前记录与账户同事务提交的创建事件。 */
    @Test
    void registerRecordsAccountCreatedOutbox() {
        when(accountMapper.findByEmail("new-user@example.com")).thenReturn(null);
        when(passwordService.encode("password123")).thenReturn("encoded");
        org.mockito.Mockito.doAnswer(invocation -> {
            AuthAccount account = invocation.getArgument(0);
            account.setId("0123456789abcdef0123456789abcdef");
            account.setCreatedAt(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            return 1;
        }).when(accountMapper).insert(any(AuthAccount.class));
        AuthOutboxRecord event = new AuthOutboxRecord("event-id",
                "0123456789abcdef0123456789abcdef", "auth.account.created", 1, "{}", "trace", NOW);
        when(accountCreatedEventFactory.create(any(AuthAccount.class))).thenReturn(event);

        authService.register("New-User@example.com ", "password123");

        verify(outboxRepository).insert(event);
        verify(outboxDispatchNotifier).notifyAfterCommit("event-id");
    }

    /** 成功刷新只能 begin 后 finish，绝不通过 createSession 复活既有 sid。 */
    @Test
    void refreshFinishesExistingRotationAndReturnsNewPair() {
        SessionService.RotationSnapshot rotation = rotation();
        when(sessionService.beginRefreshRotation("old-refresh")).thenReturn(Optional.of(rotation));
        when(accountMapper.selectById("account-1")).thenReturn(activeAccount);
        when(tokenService.newRefreshToken()).thenReturn("new-refresh");
        when(tokenService.issueAccessToken("account-1", AccountRole.USER, "sid"))
                .thenReturn(issuedAccessToken);
        when(sessionService.finishRefreshRotation(rotation, "new-refresh", AccountRole.USER,
                NOW.plusSeconds(3600), "account-1")).thenReturn(true);

        AuthService.AuthTokens result = authService.refresh("old-refresh");

        assertEquals("new-refresh", result.refreshToken());
        verify(sessionService).finishRefreshRotation(rotation, "new-refresh", AccountRole.USER,
                NOW.plusSeconds(3600), "account-1");
        verify(sessionService, never()).createSession(eq("sid"), any(), any(), any(), any(), any(), any(), anyInt());
        verify(sessionService, never()).abortRefreshRotation(any(), any());
        verify(operationalMetrics).recordRefresh(eq("success"), any(Long.class));
    }

    /** 已轮换或已注销的刷新凭据不得触发账户查询。 */
    @Test
    void consumedOrLoggedOutRefreshTokenFailsBeforeDatabaseLookup() {
        when(sessionService.beginRefreshRotation("invalid-refresh")).thenReturn(Optional.empty());

        assertThrows(AuthException.class, () -> authService.refresh("invalid-refresh"));

        verify(accountMapper, never()).selectById(any());
        verify(tokenService, never()).newRefreshToken();
        verify(operationalMetrics).recordRefresh(eq("invalid"), any(Long.class));
    }

    /** 账户不存在时旧索引已经消费，应用只会条件 abort 当前 in-flight 状态。 */
    @Test
    void missingAccountConsumesOldCredentialAndAbortsRotation() {
        SessionService.RotationSnapshot rotation = rotation();
        when(sessionService.beginRefreshRotation("old-refresh")).thenReturn(Optional.of(rotation));
        when(accountMapper.selectById("account-1")).thenReturn(null);

        AuthException exception = assertThrows(AuthException.class, () -> authService.refresh("old-refresh"));

        assertEquals(401, exception.getStatus().value());
        verify(sessionService).abortRefreshRotation(rotation, null);
        verify(tokenService, never()).newRefreshToken();
    }

    /** 禁用账户保留 403 语义，同时不会把已消费的旧 Refresh Token 恢复为可用。 */
    @Test
    void disabledAccountAbortsConsumedRotationAndReturnsForbidden() {
        SessionService.RotationSnapshot rotation = rotation();
        activeAccount.setStatus(AccountStatus.DISABLED);
        when(sessionService.beginRefreshRotation("old-refresh")).thenReturn(Optional.of(rotation));
        when(accountMapper.selectById("account-1")).thenReturn(activeAccount);

        AuthException exception = assertThrows(AuthException.class, () -> authService.refresh("old-refresh"));

        assertEquals(403, exception.getStatus().value());
        verify(sessionService).abortRefreshRotation(rotation, null);
        verify(operationalMetrics).recordRefresh(eq("disabled"), any(Long.class));
    }

    /** logout 在 begin 与 finish 之间删除会话时，finish=false 不得返回候选 Token 或调用 createSession。 */
    @Test
    void refreshDoesNotReturnTokensWhenFinishLosesSessionOwnership() {
        SessionService.RotationSnapshot rotation = rotation();
        when(sessionService.beginRefreshRotation("old-refresh")).thenReturn(Optional.of(rotation));
        when(accountMapper.selectById("account-1")).thenReturn(activeAccount);
        when(tokenService.newRefreshToken()).thenReturn("new-refresh");
        when(tokenService.issueAccessToken("account-1", AccountRole.USER, "sid"))
                .thenReturn(issuedAccessToken);
        when(sessionService.finishRefreshRotation(rotation, "new-refresh", AccountRole.USER,
                NOW.plusSeconds(3600), "account-1")).thenReturn(false);

        AuthException exception = assertThrows(AuthException.class, () -> authService.refresh("old-refresh"));

        assertEquals(401, exception.getStatus().value());
        verify(sessionService).abortRefreshRotation(rotation, "new-refresh");
        verify(sessionService, never()).createSession(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    /** 生成候选凭据时的异常也必须清理当前 nonce 所属会话，不能将旧索引恢复。 */
    @Test
    void tokenGenerationFailureAbortsRotation() {
        SessionService.RotationSnapshot rotation = rotation();
        when(sessionService.beginRefreshRotation("old-refresh")).thenReturn(Optional.of(rotation));
        when(accountMapper.selectById("account-1")).thenReturn(activeAccount);
        when(tokenService.newRefreshToken()).thenThrow(new IllegalStateException("test failure"));

        assertThrows(IllegalStateException.class, () -> authService.refresh("old-refresh"));

        verify(sessionService).abortRefreshRotation(rotation, null);
    }

    /** 注销同时删除刷新会话，并拉黑当前尚未到期的 Access Token。 */
    @Test
    void logoutDeletesSessionAndRevokesCurrentAccessToken() {
        when(tokenService.verifyAccessToken("Bearer access"))
                .thenReturn(new TokenService.AccessTokenClaims("account-1", AccountRole.USER,
                        "sid", "jti", NOW.plusSeconds(900)));

        authService.logout("Bearer access");

        verify(sessionService).deleteSession("sid", "account-1");
        verify(sessionService).revokeAccessToken("jti", NOW.plusSeconds(900));
    }

    /** 注销后，已关联的刷新令牌不能再次取得 begin 资格。 */
    @Test
    void refreshTokenCannotBeUsedAfterLogout() {
        when(tokenService.verifyAccessToken("Bearer access"))
                .thenReturn(new TokenService.AccessTokenClaims("account-1", AccountRole.USER,
                        "sid", "jti", NOW.plusSeconds(900)));
        when(sessionService.beginRefreshRotation("refresh")).thenReturn(Optional.empty());

        authService.logout("Bearer access");

        assertThrows(AuthException.class, () -> authService.refresh("refresh"));
        verify(sessionService).deleteSession("sid", "account-1");
    }

    /** 构造一条已由 begin 脚本确认、仍由当前请求持有的轮换快照。 */
    private SessionService.RotationSnapshot rotation() {
        return new SessionService.RotationSnapshot("sid", "account-1", AccountRole.USER,
                "old-hash", NOW.plusSeconds(3600), "rotation-id");
    }
}
