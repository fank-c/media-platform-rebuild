package com.calles.platform.auth.infrastructure.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.calles.platform.auth.config.AuthProperties;
import com.calles.platform.auth.domain.account.AccountRole;
import com.calles.platform.auth.exception.AuthException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Redis 会话存储边界。
 *
 * <p>Refresh Token 只以 SHA-256 哈希作为索引。刷新采用“开始消费 → 账户校验 → 条件完成”的
 * 两阶段 Lua 协议：开始阶段保留会话并标记为 in-flight，完成阶段只能更新仍属于同一请求的会话。
 * 因此注销在两阶段之间删除会话后，迟到的刷新不能重新创建该 sid。Redis 不可用时一律失败关闭，
 * 不能降级为放行令牌。</p>
 */
@Service
public class SessionService {

    /** 会话主体数据，键值由 sessionId 定位。 */
    private static final String SESSION_PREFIX = "auth:session:";
    /** Refresh Token 哈希到 sessionId 的一次性索引。 */
    private static final String REFRESH_PREFIX = "auth:refresh:";
    /** 已注销 Access Token 的 jti 黑名单，生命周期不超过原令牌。 */
    private static final String REVOKED_PREFIX = "auth:revoked:jti:";
    /** 用户设备 ID → sessionId 的映射 Hash，用于同设备重复登录的旧会话覆盖。 */
    private static final String DEVICES_PREFIX = "auth:user:devices:";
    /** 用户全局会话时序 ZSet，score 为活跃时间戳（毫秒），用于配额计数与 LRU 淘汰。 */
    private static final String SESSIONS_PREFIX = "auth:user:sessions:";
    /** Lua 受控返回：刷新资格不存在、已消费、已注销或竞争失败。 */
    private static final String INVALID = "INVALID";
    /** Lua 返回快照的固定前缀，后续内容为内部 JSON。 */
    private static final String BEGIN_OK_PREFIX = "BEGIN_OK:";

    /**
     * 新登录建立会话。所有可失败检查在写入前完成，并使用同一绝对过期时刻写入会话与索引，
     * 防止请求处理耗时意外延长 Refresh Token 生命周期。
     */
    private static final DefaultRedisScript<String> CREATE_SCRIPT =
            loadScript("redis/auth-session/create-session.lua");

    /**
     * 刷新第一阶段：消费旧索引但保留会话，并写入请求专属的 in-flight 标记。脚本返回的快照
     * 只包含内部哈希和会话元数据，绝不包含原始 Refresh Token。
     */
    private static final DefaultRedisScript<String> BEGIN_ROTATION_SCRIPT =
            loadScript("redis/auth-session/begin-refresh-rotation.lua");

    /**
     * 刷新完成阶段：仅当仍是同一 in-flight 会话时写入新索引和新哈希。先用 NX 写索引，再更新
     * 会话，in-flight 标志只在两个持久化步骤成功后移除。
     */
    private static final DefaultRedisScript<String> FINISH_ROTATION_SCRIPT =
            loadScript("redis/auth-session/finish-refresh-rotation.lua");

    /**
     * 条件终止一个未完成轮换。它只删除仍由同一 rotationId 持有的会话及关联索引，不能误删
     * logout、完成轮换或新登录已经写入的状态。
     */
    private static final DefaultRedisScript<String> ABORT_ROTATION_SCRIPT =
            loadScript("redis/auth-session/abort-refresh-rotation.lua");

    /** 注销或替换会话时有条件删除反向刷新索引，避免删除其他 sid 的新索引。 */
    private static final DefaultRedisScript<String> DELETE_SESSION_SCRIPT =
            loadScript("redis/auth-session/delete-session.lua");

    /** Redis 命令入口，所有会话状态只在认证域的键空间内读写。 */
    private final StringRedisTemplate redisTemplate;
    /** 用于解码 Lua 脚本返回的会话快照，不序列化凭据原值。 */
    private final ObjectMapper objectMapper;
    /** 提供会话 TTL，须与 Refresh Token 的对外有效期保持一致。 */
    private final AuthProperties properties;
    /** 计算黑名单和刷新绝对过期时刻，保证测试和生产使用同一时间语义。 */
    private final Clock clock;

    /**
     * @param redisTemplate 认证服务专用的 Redis 访问入口
     * @param objectMapper Spring 配置的 JSON 解码器
     * @param properties 已校验的认证配置
     * @param authClock UTC 时间源
     */
    public SessionService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                          AuthProperties properties, Clock authClock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = authClock;
    }

    /**
     * 建立新登录会话。在写入 session / refresh 索引后再写入设备映射和 ZSet，确保 abort 路径
     * 不产生 devices / sessions 残留。超限时在 Lua 内执行 LRU 淘汰，整个过程原子完成。
     *
     * @param sessionId 新生成且未复用的会话 ID
     * @param subjectId 会话所属账户 ID
     * @param role 登录时已校验的账户角色
     * @param refreshToken 仅用于在进程内计算哈希，不会写入 Redis
     * @param deadline Refresh Token 的绝对到期时刻
     * @param deviceId 客户端设备唯一标识，用于同设备重复登录覆盖旧会话
     * @param userId 用于构造 devices / sessions 键；与 subjectId 语义独立，便于未来解耦
     * @param maxSessions 单用户最大并发会话数，超限触发 LRU 淘汰
     */
    public void createSession(String sessionId, String subjectId, AccountRole role,
                              String refreshToken, Instant deadline,
                              String deviceId, String userId, int maxSessions) {
        String refreshHash = hashRefreshToken(refreshToken);
        String result = execute(CREATE_SCRIPT,
                List.of(sessionKey(sessionId), refreshKey(refreshHash),
                        devicesKey(userId), sessionsKey(userId)),
                sessionId, subjectId, role == null ? null : role.getValue(), refreshHash,
                deadline == null ? null : deadline.toString(),
                deadline == null ? null : String.valueOf(deadline.toEpochMilli()),
                deviceId, String.valueOf(maxSessions), userId);
        if ("OK".equals(result)) {
            return;
        }
        if (INVALID.equals(result)) {
            throw AuthException.sessionUnavailable();
        }
        throw AuthException.sessionUnavailable();
    }

    /**
     * 原子开始刷新轮换：消费旧 Refresh 索引、给原会话写入 in-flight 标记并返回受约束快照。
     * 返回空表示凭据已被消费、已注销、已过期或正在被另一个请求轮换；这些情况不应查询数据库。
     *
     * @param refreshToken 客户端提交的原始 Refresh Token，仅在本方法内计算哈希
     * @return 当前请求拥有的轮换快照；没有资格时为空
     */
    public Optional<RotationSnapshot> beginRefreshRotation(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        String rotationId = UUID.randomUUID().toString();
        String value = execute(BEGIN_ROTATION_SCRIPT,
                List.of(refreshKey(hashRefreshToken(refreshToken))),
                hashRefreshToken(refreshToken), rotationId);
        if (value == null || value.isBlank() || INVALID.equals(value)) {
            return Optional.empty();
        }
        if (!value.startsWith(BEGIN_OK_PREFIX)) {
            throw AuthException.sessionUnavailable();
        }
        try {
            RotationSnapshot snapshot = objectMapper.readValue(value.substring(BEGIN_OK_PREFIX.length()), RotationSnapshot.class);
            if (!rotationId.equals(snapshot.rotationId()) || snapshot.sessionId() == null || snapshot.sessionId().isBlank()
                    || snapshot.subjectId() == null || snapshot.subjectId().isBlank()
                    || snapshot.refreshHash() == null || snapshot.refreshHash().isBlank()
                    || snapshot.role() == null || snapshot.expiresAt() == null) {
                abortRefreshRotation(snapshot, null);
                throw AuthException.sessionUnavailable();
            }
            return Optional.of(snapshot);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw AuthException.sessionUnavailable();
        }
    }

    /**
     * 条件完成刷新轮换。只有收到明确 {@code OK} 才表示候选凭据可以返回客户端；会话被注销、租期耗尽
     * 或 rotationId 失配都会返回 {@code false}，调用方不得重新创建 sid。
     *
     * @param rotation begin 阶段返回的请求专属快照
     * @param newRefreshToken 本次生成的新 Refresh Token，仅用于计算哈希
     * @param latestRole 本次数据库读取到的最新角色
     * @param deadline 新 Refresh Token 的同一绝对过期时刻
     * @param userId 会话所属用户 ID，用于构造 ZSet 键（KEYS[3]）；finish 成功后 Lua 自动更新 LRU score
     * @return Redis 已确认更新时为 true；轮换归属或生命周期失效时为 false
     */
    public boolean finishRefreshRotation(RotationSnapshot rotation, String newRefreshToken,
                                         AccountRole latestRole, Instant deadline, String userId) {
        if (rotation == null || newRefreshToken == null || newRefreshToken.isBlank()
                || latestRole == null || deadline == null || !deadline.isAfter(clock.instant())
                || userId == null || userId.isBlank()) {
            throw AuthException.sessionUnavailable();
        }
        String newRefreshHash = hashRefreshToken(newRefreshToken);
        if (newRefreshHash.equals(rotation.refreshHash())) {
            throw AuthException.sessionUnavailable();
        }
        String result = execute(FINISH_ROTATION_SCRIPT,
                List.of(sessionKey(rotation.sessionId()), refreshKey(newRefreshHash), sessionsKey(userId)),
                rotation.sessionId(), rotation.subjectId(), rotation.rotationId(), rotation.refreshHash(),
                newRefreshHash, latestRole.getValue(), deadline.toString(), String.valueOf(deadline.toEpochMilli()));
        if ("OK".equals(result)) {
            return true;
        }
        if (INVALID.equals(result)) {
            return false;
        }
        throw AuthException.sessionUnavailable();
    }

    /**
     * 有条件地删除本请求遗留的 in-flight 状态。该清理是失败关闭的尽力操作：Redis 失败只能记录
     * 为 false，不能覆盖调用方已经确定的 401、403 或 500 业务结果，更不能恢复旧 Refresh Token。
     *
     * @param rotation begin 阶段返回的快照；为空时无需清理
     * @param candidateRefreshToken 已生成的候选 Refresh Token；为空时不会尝试删除候选索引
     * @return 当前请求状态被删除时为 true；状态已变化、已删除或依赖失败时为 false
     */
    public boolean abortRefreshRotation(RotationSnapshot rotation, String candidateRefreshToken) {
        if (rotation == null) {
            return false;
        }
        String candidateHash = candidateRefreshToken == null || candidateRefreshToken.isBlank()
                ? rotation.refreshHash() : hashRefreshToken(candidateRefreshToken);
        try {
            String result = redisTemplate.execute(ABORT_ROTATION_SCRIPT,
                    List.of(sessionKey(rotation.sessionId()), refreshKey(rotation.refreshHash()), refreshKey(candidateHash)),
                    rotation.sessionId(), rotation.subjectId(), rotation.rotationId());
            return "ABORTED".equals(result);
        } catch (DataAccessException ex) {
            return false;
        }
    }

    /**
     * 删除会话及其刷新令牌索引，同步清理用户设备映射和会话时序 ZSet。
     * ready 与 in-flight 会话都会删除，因此注销可阻止迟到 finish 复活 sid；
     * 删除不存在会话仍视为成功，支持客户端幂等重试。
     *
     * @param sessionId 要注销的会话 ID
     * @param userId 会话所属用户 ID，用于构造 devices / sessions 键
     */
    public void deleteSession(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String result = execute(DELETE_SESSION_SCRIPT,
                List.of(sessionKey(sessionId), devicesKey(userId), sessionsKey(userId)),
                sessionId);
        if (!"OK".equals(result)) {
            throw AuthException.sessionUnavailable();
        }
    }

    /**
     * 将当前 Access Token 加入失效列表直到其自然过期。只保存 jti，避免在 Redis 复制完整 JWT。
     *
     * @param jti 当前 Access Token 的唯一标识
     * @param expiresAt 当前 Access Token 的自然到期时刻
     */
    public void revokeAccessToken(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return;
        }
        long ttl = Duration.between(clock.instant(), expiresAt).getSeconds();
        if (ttl <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(revokedKey(jti), "1", Duration.ofSeconds(ttl));
        } catch (DataAccessException ex) {
            throw AuthException.sessionUnavailable();
        }
    }

    /**
     * 检查访问令牌是否已主动撤销。Redis 读取失败会抛出异常，调用方不得将失败误判为有效令牌。
     *
     * @param jti Access Token 唯一标识
     * @return jti 已存在于撤销列表时为 true
     */
    public boolean isAccessTokenRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(revokedKey(jti)));
        } catch (DataAccessException ex) {
            throw AuthException.sessionUnavailable();
        }
    }

    /**
     * 计算 Refresh Token 的稳定索引。该方法只用于服务端键构造，不能将哈希当作可认证凭据返回。
     *
     * @param refreshToken 原始 Refresh Token
     * @return 小写十六进制 SHA-256 哈希
     */
    public static String hashRefreshToken(String refreshToken) {
        if (refreshToken == null) {
            throw new IllegalArgumentException("refresh token must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * 从类路径加载单个 Redis Lua 文件，并在类初始化时校验脚本资源可读取。
     * 脚本与 Java 编排分离后，Redis 原子边界、键位和参数约束可在脚本内就地注释，业务服务只保留调用语义。
     *
     * @param resourcePath 位于 auth-service resources 下的 Lua 文件路径
     * @return 返回 String 受控结果的 Redis 脚本定义
     */
    private static DefaultRedisScript<String> loadScript(String resourcePath) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(resourcePath));
        script.setResultType(String.class);
        script.afterPropertiesSet();
        return script;
    }

    /**
     * 执行会话 Lua 脚本并将任何 Redis 客户端异常转换为固定 503，禁止把底层错误文本暴露给调用方。
     */
    private String execute(DefaultRedisScript<String> script, List<String> keys, String... arguments) {
        try {
            return redisTemplate.execute(script, keys, (Object[]) arguments);
        } catch (DataAccessException ex) {
            throw AuthException.sessionUnavailable();
        }
    }

    /** 构造服务私有会话键，调用方不得把原始 Refresh Token 用作 Redis 键。 */
    private String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    /** 构造 Refresh Token 哈希索引键；空候选哈希只用于 abort 的安全 no-op 位置参数。 */
    private String refreshKey(String refreshHash) {
        return REFRESH_PREFIX + refreshHash;
    }

    /** 构造 Access Token jti 的撤销键。 */
    private String revokedKey(String jti) {
        return REVOKED_PREFIX + jti;
    }

    /**
     * 构造用户设备映射键。该 Hash 存储 deviceId → sessionId 的映射，
     * 用于同设备重复登录时定位并覆盖旧会话，保证同一设备只占用一个会话配额。
     */
    private String devicesKey(String userId) {
        return DEVICES_PREFIX + userId;
    }

    /**
     * 构造用户会话时序键。该 ZSet 以活跃时间戳为 score 记录用户全量有效会话，
     * 用于配额计数（ZCARD）和 LRU 淘汰（ZPOPMIN），刷新成功后更新 score 保持活跃。
     */
    private String sessionsKey(String userId) {
        return SESSIONS_PREFIX + userId;
    }

    /**
     * begin 阶段返回的会话快照，只可由同一请求的 finish/abort 使用。rotationId 是内部归属标记，
     * 不是可续租、可接管的分布式锁；进程崩溃时 in-flight 会话随原 TTL 自然到期。
     */
    public record RotationSnapshot(String sessionId, String subjectId, AccountRole role,
                                   String refreshHash, Instant expiresAt, String rotationId) {
    }
}
