package com.calles.platform.auth.application;

import com.calles.platform.auth.application.outbox.AuthOutboxDispatchNotifier;
import com.calles.platform.auth.domain.account.AccountRole;
import com.calles.platform.auth.domain.account.AccountStatus;
import com.calles.platform.auth.domain.account.AuthAccount;
import com.calles.platform.auth.exception.AuthException;
import com.calles.platform.auth.infrastructure.persistence.AuthAccountMapper;
import com.calles.platform.auth.infrastructure.security.PasswordService;
import com.calles.platform.auth.infrastructure.security.SessionService;
import com.calles.platform.auth.infrastructure.security.TokenService;
import com.calles.platform.auth.infrastructure.messaging.AccountCreatedEventFactory;
import com.calles.platform.auth.infrastructure.outbox.AuthOutboxRepository;
import com.calles.platform.auth.infrastructure.observability.AuthOperationalMetrics;
import com.calles.platform.auth.interfaces.http.dto.CurrentUserResponse;
import com.calles.platform.auth.interfaces.http.dto.RegisterResponse;
import com.calles.platform.auth.interfaces.http.dto.VerifyTokenResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务应用层核心服务，负责认证用例的编排和业务流程控制。
 *
 * <p>职责范围：
 * <ul>
 *   <li>用户注册：创建普通用户账户</li>
 *   <li>用户登录：验证凭据并签发令牌</li>
 *   <li>令牌刷新：使用 Refresh Token 换取新令牌</li>
 *   <li>用户注销：撤销令牌和会话</li>
 *   <li>令牌验证：验证 Token 有效性（供网关调用）</li>
 *   <li>获取用户信息：通过 Token 查询账户信息（供前端调用）</li>
 * </ul>
 *
 * <p>设计原则：
 * <ul>
 *   <li>单一职责：仅处理认证相关业务，不涉及用户资料管理</li>
 *   <li>依赖隔离：只访问 auth_account 表，不跨服务调用</li>
 *   <li>安全优先：密码加密、Token 轮换、会话管理严格遵循安全最佳实践</li>
 * </ul>
 *
 * <p>当前服务只写入 {@code auth_account}；注册后的资料创建不属于此用例。网关可通过
 * {@code /verify} 获取经过验证的主体声明。</p>
 */
@Service
public class AuthService {

    /** 账户数据访问对象，操作 auth_account 表 */
    private final AuthAccountMapper accountMapper;

    /** 密码服务，负责密码的 BCrypt 加密和验证 */
    private final PasswordService passwordService;

    /** 令牌服务，负责 JWT 的签发和验证 */
    private final TokenService tokenService;

    /** 会话服务，负责 Redis 会话管理和令牌黑名单 */
    private final SessionService sessionService;

    /** Refresh Token 有效期（默认 30 天） */
    private final java.time.Duration refreshTokenTtl;

    /** Access Token 有效期秒数（默认 15 分钟） */
    private final long accessTokenTtlSeconds;

    /** 时钟，用于获取当前时间（便于测试） */
    private final Clock clock;

    /** 账号创建事件工厂，只构造最小化的版本化事件。 */
    private final AccountCreatedEventFactory accountCreatedEventFactory;

    /** 认证域 Outbox 仓储，与账户写入共用本地数据库事务。 */
    private final AuthOutboxRepository outboxRepository;

    /** 注册事务成功提交后发出快速唤醒提示的窄接口；不承担可靠发送。 */
    private final AuthOutboxDispatchNotifier outboxDispatchNotifier;

    /** 认证流程低基数指标，只记录固定结果类别，不记录 sid、nonce 或任何凭据。 */
    private final AuthOperationalMetrics operationalMetrics;

    /**
     * 构造函数，通过 Spring 依赖注入初始化所有依赖。
     *
     * @param accountMapper 账户数据访问对象
     * @param passwordService 密码服务
     * @param tokenService 令牌服务
     * @param sessionService 会话服务
     * @param properties 认证配置属性
     * @param authClock 时钟（用于时间相关逻辑）
     */
    public AuthService(AuthAccountMapper accountMapper, PasswordService passwordService,
                       TokenService tokenService, SessionService sessionService,
                       com.calles.platform.auth.config.AuthProperties properties, Clock authClock,
                       AccountCreatedEventFactory accountCreatedEventFactory,
                       AuthOutboxRepository outboxRepository,
                       AuthOutboxDispatchNotifier outboxDispatchNotifier,
                       AuthOperationalMetrics operationalMetrics) {
        this.accountMapper = accountMapper;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.refreshTokenTtl = properties.refreshTokenTtl();
        this.accessTokenTtlSeconds = properties.getAccessTokenTtlSeconds();
        this.clock = authClock;
        this.accountCreatedEventFactory = accountCreatedEventFactory;
        this.outboxRepository = outboxRepository;
        this.outboxDispatchNotifier = outboxDispatchNotifier;
        this.operationalMetrics = operationalMetrics;
    }

    /**
     * 用户登录。
     *
     * <p>验证用户名和密码，成功后签发访问令牌和刷新令牌。
     *
     * @param loginName 登录名
     * @param password 明文密码
     * @return 包含访问令牌、刷新令牌、有效期、角色的认证令牌对象
     * @throws AuthException.invalidRequest 密码格式不合法
     * @throws AuthException.invalidCredentials 用户名或密码错误
     * @throws AuthException.accountDisabled 账户已被禁用
     */
    public AuthTokens login(String loginName, String password) {
        // 规范化登录名（trim 处理）
        String normalizedLoginName = normalizeLoginName(loginName);

        // 密码基本校验
        if (password == null || password.isBlank()) {
            throw AuthException.invalidRequest("password 不能为空");
        }
        // BCrypt 只处理前 72 个 UTF-8 字节，超出部分会被截断
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw AuthException.invalidRequest("password 不能超过 72 个 UTF-8 字节");
        }

        // 查询账户
        AuthAccount account = accountMapper.findByLoginName(normalizedLoginName);
        if (account == null) {
            // 用户不存在，返回统一的"用户名或密码错误"，防止用户枚举
            throw AuthException.invalidCredentials();
        }

        // 检查账户状态
        if (account.getStatus() == AccountStatus.DISABLED) {
            throw AuthException.accountDisabled();
        }

        // 验证密码（使用 BCrypt 比对）
        if (account.getStatus() != AccountStatus.ACTIVE
                || account.getRole() == null
                || !passwordService.matches(password, account.getPasswordHash())) {
            // 密码错误或账户状态异常，返回统一错误
            throw AuthException.invalidCredentials();
        }

        // 签发令牌并创建会话
        return issueLoginTokens(account);
    }

    /**
     * 刷新令牌。
     *
     * <p>使用 Refresh Token 换取新的 Access Token 和 Refresh Token。
     * 采用令牌轮换（Token Rotation）策略，旧 Refresh Token 消费后立即失效，防止重放攻击。
     *
     * <p>刷新流程：
     * <ol>
     *   <li>验证 Refresh Token 非空，并由 Redis Lua 原子消费旧索引、将原会话标记为 in-flight</li>
     *   <li>仅在 begin 成功后，根据会话快照中的 subjectId 查询账户并校验最新状态</li>
     *   <li>在当前请求内存生成候选 Token；此时尚未创建或覆盖 Redis 会话</li>
     *   <li>仅当同一 rotationId 仍拥有原 sid 时完成轮换，随后返回候选令牌对</li>
     * </ol>
     *
     * <p>安全设计：
     * <ul>
     *   <li>令牌轮换：旧 Refresh Token 在 begin 成功后立即不可重放，失败不会恢复旧索引</li>
     *   <li>会话归属：logout 在 begin 与 finish 之间删除 sid 后，finish 只能返回失败，不能复活会话</li>
     *   <li>结果确认：Redis 未明确确认 finish 成功时不返回候选 Token，也不走登录建会话路径</li>
     *   <li>账户状态校验：每次刷新都检查账户最新状态</li>
     * </ul>
     *
     * @param refreshToken Refresh Token（明文）
     * @return 包含新访问令牌、新刷新令牌、有效期、角色的认证令牌对象
     * @throws AuthException.invalidRefreshToken Refresh Token 无效、已过期或已被消费
     * @throws AuthException.accountDisabled 账户已被禁用
     */
    public AuthTokens refresh(String refreshToken) {
        long startedNanos = System.nanoTime();
        String outcome = "failed";
        SessionService.RotationSnapshot rotation = null;
        String candidateRefreshToken = null;
        boolean finishConfirmed = false;
        try {
            // 先由 Redis 原子取得轮换资格；失败时不得查询账户或生成候选凭据。
            if (refreshToken == null || refreshToken.isBlank()) {
                outcome = "invalid";
                throw AuthException.invalidRefreshToken();
            }
            Optional<SessionService.RotationSnapshot> begun = sessionService.beginRefreshRotation(refreshToken);
            if (begun.isEmpty()) {
                outcome = "invalid";
                throw AuthException.invalidRefreshToken();
            }
            rotation = begun.get();

            // 旧索引已经消费后才读取数据库，保留既有“账户校验失败也不能重放旧凭据”的契约。
            AuthAccount account = accountMapper.selectById(rotation.subjectId());
            if (account == null || account.getRole() == null) {
                outcome = "invalid";
                throw AuthException.invalidRefreshToken();
            }
            if (account.getStatus() == AccountStatus.DISABLED) {
                outcome = "disabled";
                throw AuthException.accountDisabled();
            }
            if (account.getStatus() != AccountStatus.ACTIVE) {
                outcome = "invalid";
                throw AuthException.invalidRefreshToken();
            }

            // 候选令牌只保存在当前请求内存；deadline 固定一次，处理耗时不会延长新会话寿命。
            Instant deadline = clock.instant().plus(refreshTokenTtl);
            candidateRefreshToken = tokenService.newRefreshToken();
            TokenService.IssuedAccessToken accessToken = tokenService.issueAccessToken(
                    account.getId(), account.getRole(), rotation.sessionId());

            // 仅 Redis 明确确认同一 in-flight 会话已更新时，才允许向客户端返回候选令牌对。
            if (!sessionService.finishRefreshRotation(rotation, candidateRefreshToken, account.getRole(), deadline)) {
                outcome = "invalid";
                throw AuthException.invalidRefreshToken();
            }
            finishConfirmed = true;
            outcome = "success";
            return new AuthTokens(accessToken.value(), candidateRefreshToken, accessTokenTtlSeconds, account.getRole());
        } catch (AuthException ex) {
            if ("failed".equals(outcome)) {
                outcome = ex.getStatus().is5xxServerError() ? "unavailable" : "failed";
            }
            throw ex;
        } finally {
            // 已知失败和结果未知都只做带 rotationId 的条件清理，绝不恢复旧索引或无条件删除新状态。
            if (rotation != null && !finishConfirmed) {
                boolean aborted = sessionService.abortRefreshRotation(rotation, candidateRefreshToken);
                // abort 失败不会覆盖已经确定的 401、403 或 503，但必须留下独立指标供排查悬挂状态。
                operationalMetrics.recordRefreshAbort(aborted);
            }
            operationalMetrics.recordRefresh(outcome, System.nanoTime() - startedNanos);
        }
    }

    /**
     * 用户注销。
     *
     * <p>终止当前会话，使 Access Token 和 Refresh Token 立即失效。
     *
     * <p>注销流程：
     * <ol>
     *   <li>验证 Access Token 签名和有效期</li>
     *   <li>删除 Redis 中的会话（使 Refresh Token 无法再换发新令牌）</li>
     *   <li>将 Access Token 的 jti 加入黑名单（使当前 Access Token 立即失效）</li>
     * </ol>
     *
     * <p>双重失效机制：
     * <ul>
     *   <li>删除会话：Refresh Token 失效，无法换发新的 Access Token</li>
     *   <li>黑名单拉黑：当前 Access Token 在剩余有效期内立即失效</li>
     * </ul>
     *
     * <p>黑名单 TTL 设计：
     * <ul>
     *   <li>黑名单记录的 TTL 等于 Access Token 的剩余有效期</li>
     *   <li>Token 自然过期后，黑名单记录自动删除，节省存储空间</li>
     * </ul>
     *
     * @param authorizationHeader Authorization Header，格式为 "Bearer {token}"
     * @throws AuthException.invalidAccessToken Access Token 无效或已过期
     */
    public void logout(String authorizationHeader) {
        // 验证并解析 Access Token
        TokenService.AccessTokenClaims claims = tokenService.verifyAccessToken(authorizationHeader);

        // 删除会话（使 Refresh Token 无法再换发新令牌）
        sessionService.deleteSession(claims.sessionId());

        // 将 Access Token 的 jti 加入黑名单（使当前 Access Token 立即失效）
        // TTL 设置为 Token 的剩余有效期，过期后自动删除
        sessionService.revokeAccessToken(claims.jti(), claims.expiresAt());
    }

    /**
     * 注册新用户账户。当前只支持普通用户角色，管理员账户由运维手动创建。
     *
     * <p>注册流程：
     * <ol>
     *   <li>规范化登录名（trim 处理）</li>
     *   <li>检查用户名是否已存在（大小写敏感）</li>
     *   <li>验证密码长度（8-72 字符，BCrypt 限制）</li>
     *   <li>使用 BCrypt 加密密码（不可逆）</li>
     *   <li>创建账户，角色固定为 USER，状态为 ACTIVE</li>
     * </ol>
     *
     * <p>安全考虑：
     * <ul>
     *   <li>密码立即 BCrypt 加密，明文密码不落盘</li>
     *   <li>注册成功后不自动登录，需要调用 /login 接口</li>
     *   <li>用户名唯一性由数据库唯一索引保证</li>
     * </ul>
     *
     * @param loginName 登录名，会自动 trim
     * @param password 明文密码，长度 8-72 字符
     * @return 注册成功响应，包含账户基本信息（不含敏感信息）
     * @throws AuthException.loginNameAlreadyExists 用户名已存在
     * @throws AuthException.invalidRequest 密码格式不合法
     */
    @Transactional
    public RegisterResponse register(String loginName, String password) {
        // 规范化登录名（trim 处理，移除首尾空格）
        String normalizedLoginName = normalizeLoginName(loginName);

        // 检查用户名是否已存在（大小写敏感）
        AuthAccount existing = accountMapper.findByLoginName(normalizedLoginName);
        if (existing != null) {
            // 用户名已存在，返回 409 Conflict
            throw AuthException.loginNameAlreadyExists();
        }

        // 密码长度验证（已在 DTO 层通过 @Size 验证，此处为防御性检查）
        if (password == null || password.isBlank()) {
            throw AuthException.invalidRequest("password 不能为空");
        }
        if (password.length() < 8) {
            // 最小长度 8 字符，满足基本安全要求
            throw AuthException.invalidRequest("password 长度必须至少 8 个字符");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            // BCrypt 算法限制：只处理前 72 个 UTF-8 字节，超出部分会被忽略
            // 为避免用户误解，明确拒绝过长密码
            throw AuthException.invalidRequest("password 不能超过 72 个 UTF-8 字节");
        }

        // 创建账户实体
        AuthAccount account = new AuthAccount();
        account.setLoginName(normalizedLoginName);
        // 使用 BCrypt 加密密码（不可逆，成本因子默认为 10）
        account.setPasswordHash(passwordService.encode(password));
        // 角色固定为普通用户，管理员账户由运维手动创建
        account.setRole(AccountRole.USER);
        // 新注册账户默认为激活状态
        account.setStatus(AccountStatus.ACTIVE);

        // 插入数据库（UUID 主键由 MyBatis-Plus 自动生成）
        accountMapper.insert(account);

        // 账户与事件必须在同一数据库事务中提交，Broker 故障不能制造不可补偿的资料缺口。
        var outboxRecord = accountCreatedEventFactory.create(account);
        outboxRepository.insert(outboxRecord);
        // 回调只会在本地事务成功提交后非阻塞提交 eventId；拒绝时由持久化扫描恢复。
        outboxDispatchNotifier.notifyAfterCommit(outboxRecord.eventId());

        // 返回账户基本信息（不含密码哈希等敏感信息）
        return new RegisterResponse(
                account.getId(),
                account.getLoginName(),
                account.getRole().getValue(),
                account.getStatus().name()
        );
    }

    /**
     * 验证访问令牌并返回身份信息。供网关和其他服务调用。
     * 检查 Token 签名、有效期以及是否被注销。
     *
     * <p>验证流程：
     * <ol>
     *   <li>自动处理 "Bearer " 前缀（有无均可）</li>
     *   <li>验证 JWT 签名和有效期</li>
     *   <li>检查 Token 是否在黑名单中（已注销）</li>
     *   <li>返回用户身份信息或 invalid 响应</li>
     * </ol>
     *
     * <p>使用场景：
     * <ul>
     *   <li>API 网关验证请求 Token</li>
     *   <li>服务间调用验证调用方身份</li>
     *   <li>需要快速判断 Token 有效性的场景</li>
     * </ul>
     *
     * <p>返回 invalid 的情况：
     * <ul>
     *   <li>JWT 签名验证失败</li>
     *   <li>Token 已过期</li>
     *   <li>Token 在黑名单中（用户已注销）</li>
     *   <li>Token 格式错误</li>
     * </ul>
     *
     * <p>注意：此方法不抛出异常，验证失败返回 valid=false 的响应。
     * 这样设计是为了方便网关统一处理，避免异常传播。
     *
     * @param token 访问令牌，可以带或不带 "Bearer " 前缀
     * @return 验证结果，valid=true 时包含用户身份信息，valid=false 时其他字段为 null
     */
    public VerifyTokenResponse verifyToken(String token) {
        try {
            // 自动处理 "Bearer " 前缀（兼容两种格式）
            // 如果 token 已经包含 "Bearer " 前缀，直接使用；否则添加前缀
            String authHeader = token != null && token.startsWith("Bearer ") ? token : "Bearer " + token;

            // 验证 JWT 签名和有效期（会抛出 AuthException 如果验证失败）
            TokenService.AccessTokenClaims claims = tokenService.verifyAccessToken(authHeader);

            // 检查 Token 是否已被注销（在 Redis 黑名单中）
            if (sessionService.isAccessTokenRevoked(claims.jti())) {
                // Token 已注销，返回 invalid
                return VerifyTokenResponse.invalid();
            }

            // 根据角色生成用户类型（前端可以用来快速判断权限）
            String type = claims.role() == AccountRole.ADMIN ? "admin" : "user";

            // 返回验证成功响应，包含用户身份信息
            return VerifyTokenResponse.valid(
                    claims.subject(),           // 账户 ID
                    type,                       // 用户类型（admin/user）
                    claims.role().getValue(),   // 角色枚举值（USER/ADMIN）
                    claims.sessionId(),         // 会话 ID
                    claims.expiresAt().toEpochMilli()  // Token 过期时间戳（毫秒）
            );
        } catch (AuthException ex) {
            // 任何验证异常（签名错误、过期等）都返回 invalid，不抛出异常
            // 这样设计方便网关统一处理，避免异常传播
            return VerifyTokenResponse.invalid();
        }
    }

    /**
     * 获取当前用户信息。通过 Authorization Header 中的 Token 获取。
     *
     * <p>验证流程：
     * <ol>
     *   <li>验证 JWT 签名和有效期</li>
     *   <li>检查 Token 是否在黑名单中（已注销）</li>
     *   <li>从数据库查询账户最新状态</li>
     *   <li>检查账户是否被禁用</li>
     *   <li>返回用户身份信息</li>
     * </ol>
     *
     * <p>与 verifyToken 的区别：
     * <ul>
     *   <li>verifyToken：仅验证 Token，不查询数据库，返回 valid 字段</li>
     *   <li>getCurrentUser：验证 Token 并查询账户最新状态，失败时抛出异常</li>
     * </ul>
     *
     * <p>使用场景：
     * <ul>
     *   <li>前端页面初始化获取用户信息</li>
     *   <li>用户刷新页面后恢复登录状态</li>
     *   <li>需要获取账户最新状态的场景</li>
     * </ul>
     *
     * <p>注意：此方法会查询数据库，性能略低于 verifyToken。
     * 如果只需验证 Token 有效性而不需要最新账户状态，建议使用 verifyToken。
     *
     * @param authorizationHeader Authorization Header，格式为 "Bearer {token}"
     * @return 当前用户信息，包含账户 ID、登录名、角色、会话 ID
     * @throws AuthException.invalidAccessToken Token 无效、已过期或账户不存在
     * @throws AuthException.accountDisabled 账户已被禁用
     */
    public CurrentUserResponse getCurrentUser(String authorizationHeader) {
        // 验证 JWT 签名和有效期（会抛出 AuthException 如果验证失败）
        TokenService.AccessTokenClaims claims = tokenService.verifyAccessToken(authorizationHeader);

        // 检查 Token 是否已被注销（在 Redis 黑名单中）
        if (sessionService.isAccessTokenRevoked(claims.jti())) {
            // Token 已注销（用户已登出），返回 401 错误
            throw AuthException.invalidAccessToken();
        }

        // 从数据库查询账户最新状态（确保获取最新的角色和状态信息）
        AuthAccount account = accountMapper.selectById(claims.subject());
        if (account == null) {
            // 账户不存在（可能已被删除），返回 401 错误
            throw AuthException.invalidAccessToken();
        }

        // 检查账户是否被禁用
        if (account.getStatus() == AccountStatus.DISABLED) {
            // 账户已被管理员禁用，返回 403 错误
            throw AuthException.accountDisabled();
        }

        // 根据角色生成用户类型（前端可以用来快速判断权限）
        String type = account.getRole() == AccountRole.ADMIN ? "admin" : "user";

        // 返回当前用户信息
        return new CurrentUserResponse(
                account.getId(),            // 账户 ID
                account.getLoginName(),     // 登录名
                account.getRole().getValue(), // 角色枚举值（USER/ADMIN）
                type,                       // 用户类型（admin/user）
                claims.sessionId()          // 会话 ID（用于后续注销操作）
        );
    }

    /**
     * 为新登录签发令牌并创建会话。刷新流程不得调用本方法，因为它会生成新的 sid；刷新只能在
     * {@link #refresh(String)} 中对 begin 阶段确认的既有 sid 执行条件 finish。
     *
     * <p>令牌签发流程：
     * <ol>
     *   <li>生成新的 sessionId 和随机 Refresh Token</li>
     *   <li>签发包含账户、角色、sessionId 和 jti 的 Access Token</li>
     *   <li>以同一绝对 deadline 创建 Redis 会话和 Refresh Token 哈希索引</li>
     *   <li>只有会话创建明确成功后才返回令牌对</li>
     * </ol>
     *
     * @param account 已完成凭据和状态校验的账户
     * @return 包含访问令牌、刷新令牌、有效期、角色的认证令牌对象
     */
    private AuthTokens issueLoginTokens(AuthAccount account) {
        // 新登录必须生成新 sid；刷新不能调用此方法，避免在 logout 后重新创建旧会话。
        String sessionId = tokenService.newSessionId();

        // Refresh Token 与 Access Token 仅在 Redis 会话建立成功后才作为登录结果返回。
        Instant sessionDeadline = clock.instant().plus(refreshTokenTtl);
        String refreshToken = tokenService.newRefreshToken();
        TokenService.IssuedAccessToken accessToken = tokenService.issueAccessToken(
                account.getId(), account.getRole(), sessionId);

        // 会话与哈希索引使用相同绝对 deadline，Redis 失败则整体拒绝本次登录。
        sessionService.createSession(sessionId, account.getId(), account.getRole(), refreshToken, sessionDeadline);
        return new AuthTokens(accessToken.value(), refreshToken, accessTokenTtlSeconds, account.getRole());
    }

    /**
     * 规范化登录名（trim 处理，移除首尾空格）。
     *
     * <p>规范化规则：
     * <ul>
     *   <li>移除首尾空格（trim）</li>
     *   <li>空字符串或纯空格视为无效</li>
     *   <li>大小写保持不变（大小写敏感）</li>
     * </ul>
     *
     * @param loginName 原始登录名
     * @return 规范化后的登录名
     * @throws AuthException.invalidRequest 登录名为空或纯空格
     */
    private String normalizeLoginName(String loginName) {
        if (loginName == null || loginName.isBlank()) {
            throw AuthException.invalidRequest("loginName 不能为空");
        }
        // 移除首尾空格
        return loginName.trim();
    }

    /**
     * 认证令牌响应对象。
     *
     * <p>包含登录和刷新令牌接口返回的所有令牌信息。
     *
     * @param accessToken 访问令牌（JWT），用于 API 请求认证
     * @param refreshToken 刷新令牌（随机字符串），用于换取新的访问令牌
     * @param expiresIn 访问令牌有效期（秒），前端可用于倒计时或提前刷新
     * @param role 用户角色，前端可用于权限判断
     */
    public record AuthTokens(String accessToken, String refreshToken, long expiresIn,
                             AccountRole role) {
    }
}
