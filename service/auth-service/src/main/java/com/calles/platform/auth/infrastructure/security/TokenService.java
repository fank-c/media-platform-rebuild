package com.calles.platform.auth.infrastructure.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.calles.platform.auth.config.AuthProperties;
import com.calles.platform.auth.domain.account.AccountRole;
import com.calles.platform.auth.exception.AuthException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与验证边界。
 *
 * <p>Access Token 只保存跨服务认证所需声明；Refresh Token 保持为高熵、不透明随机值，
 * 其生命周期由 {@link SessionService} 管理。验签时同时校验签名、签发方、受众及声明间约束，
 * 不能仅因签名正确就信任令牌。</p>
 */
@Service
public class TokenService {

    /** HTTP Authorization 头使用的标准凭据前缀。 */
    private static final String BEARER_PREFIX = "Bearer ";
    /** 刷新令牌必须使用密码学安全随机源，不能用 UUID 替代。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 令牌签发参数，初始化时已完成安全校验。 */
    private final AuthProperties properties;
    /** 统一令牌时间来源，保证过期判断可测试且不受本地时区影响。 */
    private final Clock clock;
    /** 使用受保护密钥构造的 HMAC-SHA256 签名算法。 */
    private final Algorithm algorithm;
    /** 固定 issuer 和 audience 的验证器，拒绝跨环境或跨系统令牌。 */
    private final JWTVerifier verifier;

    public TokenService(AuthProperties properties, Clock authClock) {
        // 单元测试可能绕过配置类的 PostConstruct，因此在安全边界再次校验。
        properties.validate();
        this.properties = properties;
        this.clock = authClock;
        this.algorithm = Algorithm.HMAC256(properties.getJwtSecret());
        this.verifier = JWT.require(algorithm)
                .withIssuer(properties.getJwtIssuer())
                .withAudience(properties.getJwtAudience())
                .build();
    }

    /** 生成会话标识，用于关联 Access Token、Refresh Token 与 Redis 状态。 */
    public String newSessionId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 生成 256 位随机刷新令牌。该原值仅返回客户端，Redis 只保存其 SHA-256 哈希。
     */
    public String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 签发短期 Access Token。角色同时写入 {@code roles} 与 {@code typ}，验证时会检查二者一致，
     * 防止可验证但内部声明互相矛盾的令牌进入下游服务。
     */
    public IssuedAccessToken issueAccessToken(String subject, AccountRole role, String sessionId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        String jti = UUID.randomUUID().toString();
        String typ = role == AccountRole.ADMIN ? "admin" : "user";
        String token = JWT.create()
                .withSubject(subject)
                .withClaim("typ", typ)
                .withClaim("roles", List.of(role.getValue()))
                .withJWTId(jti)
                .withClaim("sid", sessionId)
                .withIssuer(properties.getJwtIssuer())
                .withAudience(properties.getJwtAudience())
                .withIssuedAt(Date.from(issuedAt))
                .withExpiresAt(Date.from(expiresAt))
                .sign(algorithm);
        return new IssuedAccessToken(token, jti, sessionId, expiresAt);
    }

    /**
     * 验证 Bearer Access Token 并返回标准化声明。任何解析、过期或声明完整性错误统一转换为
     * {@link AuthException}，调用方不得基于底层 JWT 异常作分支。
     */
    public AccessTokenClaims verifyAccessToken(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        try {
            DecodedJWT decoded = verifier.verify(token);
            String subject = decoded.getSubject();
            String sessionId = decoded.getClaim("sid").asString();
            String type = decoded.getClaim("typ").asString();
            List<String> roles = decoded.getClaim("roles").asList(String.class);
            String jti = decoded.getId();
            if (subject == null || subject.isBlank() || sessionId == null || sessionId.isBlank()
                    || jti == null || jti.isBlank() || roles == null || roles.isEmpty()
                    || decoded.getIssuedAt() == null || decoded.getExpiresAt() == null) {
                throw AuthException.invalidAccessToken();
            }
            AccountRole role = parseRole(roles.get(0));
            String expectedType = role == AccountRole.ADMIN ? "admin" : "user";
            if (!expectedType.equals(type)) {
                throw AuthException.invalidAccessToken();
            }
            return new AccessTokenClaims(subject, role, sessionId, jti, decoded.getExpiresAt().toInstant());
        } catch (AuthException ex) {
            throw ex;
        } catch (JWTVerificationException | IllegalArgumentException | NullPointerException ex) {
            throw AuthException.invalidAccessToken();
        }
    }

    /**
     * 从标准 {@code Authorization: Bearer <token>} 请求头提取令牌。拒绝额外空白，避免不同代理
     * 对同一请求头产生不一致的解析结果。
     */
    public String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw AuthException.invalidAccessToken();
        }
        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
            throw AuthException.invalidAccessToken();
        }
        return token;
    }

    /** 将 JWT 中受限的角色值还原为枚举，未知值一律拒绝。 */
    private AccountRole parseRole(String value) {
        for (AccountRole role : AccountRole.values()) {
            if (role.getValue().equals(value)) {
                return role;
            }
        }
        throw AuthException.invalidAccessToken();
    }

    /** 令牌签发结果，供应用层写入会话和组装 HTTP 响应。 */
    public record IssuedAccessToken(String value, String jti, String sessionId, Instant expiresAt) {
    }

    /** 通过严格验签后的认证声明；不得直接由外部请求构造为可信身份。 */
    public record AccessTokenClaims(String subject, AccountRole role, String sessionId, String jti,
                                    Instant expiresAt) {
    }
}
