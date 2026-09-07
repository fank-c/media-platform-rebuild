package com.calles.platform.auth.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.calles.platform.auth.config.AuthProperties;
import com.calles.platform.auth.domain.account.AccountRole;
import com.calles.platform.auth.exception.AuthException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** JWT 签发与验证测试，覆盖标准声明、篡改和过期边界。 */
class TokenServiceTest {

    /** 固定签发时间，确保过期令牌的构造和断言可重复。 */
    private static final Instant NOW = Instant.now();
    /** 仅测试用的 32 字节密钥，不对应任何环境凭据。 */
    private static final String SECRET = "01234567890123456789012345678901";

    private TokenService tokenService;

    /** 每个用例使用固定时钟重建服务，隔离 JWT 时间状态。 */
    @BeforeEach
    void setUp() {
        AuthProperties properties = properties();
        tokenService = new TokenService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 新签发的令牌必须完整保留主体、角色、会话和唯一标识声明。 */
    @Test
    void issuesAndVerifiesAccessTokenClaims() {
        TokenService.IssuedAccessToken issued = tokenService.issueAccessToken("account-1",
                AccountRole.ADMIN, "session-1");

        TokenService.AccessTokenClaims claims = tokenService.verifyAccessToken("Bearer " + issued.value());

        assertEquals("account-1", claims.subject());
        assertEquals(AccountRole.ADMIN, claims.role());
        assertEquals("session-1", claims.sessionId());
        assertEquals(issued.jti(), claims.jti());
    }

    /** 签名后的任意字节变更都必须使 JWT 验证失败。 */
    @Test
    void rejectsTamperedToken() {
        TokenService.IssuedAccessToken issued = tokenService.issueAccessToken("account-1",
                AccountRole.USER, "session-1");

        assertThrows(AuthException.class,
                () -> tokenService.verifyAccessToken("Bearer " + issued.value() + "x"));
    }

    /** 即使签名正确，过期令牌也不得被接受。 */
    @Test
    void rejectsExpiredToken() {
        AuthProperties properties = properties();
        Algorithm algorithm = Algorithm.HMAC256(SECRET);
        String expired = JWT.create()
                .withSubject("account-1")
                .withClaim("typ", "user")
                .withClaim("roles", List.of("USER"))
                .withJWTId("jti-1")
                .withClaim("sid", "session-1")
                .withIssuer(properties.getJwtIssuer())
                .withAudience(properties.getJwtAudience())
                .withIssuedAt(Date.from(NOW.minusSeconds(120)))
                .withExpiresAt(Date.from(NOW.minusSeconds(60)))
                .sign(algorithm);

        assertThrows(AuthException.class, () -> tokenService.verifyAccessToken("Bearer " + expired));
    }

    /** 创建满足启动校验的最小认证配置，避免测试依赖环境变量。 */
    private AuthProperties properties() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret(SECRET);
        properties.setJwtIssuer("auth-service");
        properties.setJwtAudience("media-platform");
        properties.setAccessTokenTtlSeconds(900);
        properties.setRefreshTokenTtlSeconds(3600);
        return properties;
    }
}
