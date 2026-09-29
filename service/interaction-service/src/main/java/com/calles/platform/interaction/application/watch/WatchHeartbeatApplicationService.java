package com.calles.platform.interaction.application.watch;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.config.InteractionWatchProperties;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.video.VideoSnapshot;
import com.calles.platform.interaction.domain.model.watch.WatchCreditValidator;
import com.calles.platform.interaction.domain.model.watch.WatchEventClaim;
import com.calles.platform.interaction.domain.model.watch.WatchEventType;
import com.calles.platform.interaction.domain.model.watch.WatchProgress;
import com.calles.platform.interaction.domain.model.watch.WatchQualificationPolicy;
import com.calles.platform.interaction.domain.model.watch.WatchSession;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.VideoSnapshotRepository;
import com.calles.platform.interaction.domain.repository.WatchEventClaimRepository;
import com.calles.platform.interaction.domain.repository.WatchProgressRepository;
import com.calles.platform.interaction.domain.repository.WatchSessionRepository;
import com.calles.platform.interaction.exception.WatchSessionActiveException;
import com.calles.platform.interaction.exception.WatchSessionExpiredException;
import com.calles.platform.interaction.exception.WatchSessionInvalidException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 观看心跳应用服务：起播计数与观看行为事件彻底解耦后的心跳实现。
 *
 * <p>核心架构与职责：
 * <ul>
 *   <li><b>并发控制</b>：以 {@code SELECT ... FOR UPDATE} 锁定 {@code interaction_watch_progress} 行作为唯一串行化入口，
 *       用例入口读取统一 Clock 的时间快照；首次心跳并发插入由唯一键兜底退化为行锁；</li>
 *   <li><b>起播计数</b>：新会话创建时，若视频已发布且已满冷却窗口，立即在本地事务写入 {@code WATCH_PLAY} 增量 (+1)
 *       并记录会话 {@code view_counted_at} 与进度 {@code last_view_claimed_at}；退出仍保留，不要求 5 秒或 30% 时长；</li>
 *   <li><b>合格观看事件</b>：后续心跳中会话有效时长达到 30% / 5 秒门槛时发出，不触碰播放量、不读冷却、不改冷却时间；</li>
 *   <li><b>完播事件</b>：后续心跳中播放位置与有效时长双 90% 达标时发出；</li>
 *   <li><b>起播幂等与会话恢复</b>：同幂等键重试返回只读回执；键不存在而活跃会话仍存活时抛出 409 要求恢复现有会话；</li>
 *   <li><b>降级与异常</b>：无快照或未发布内容只保存断点不计数；会话不匹配或超时分别抛出 409。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WatchHeartbeatApplicationService {

    private final WatchProgressRepository progressRepository;
    private final WatchSessionRepository sessionRepository;
    private final WatchEventClaimRepository claimRepository;
    private final VideoSnapshotRepository videoSnapshotRepository;
    private final CounterDeltaRepository counterDeltaRepository;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionWatchProperties properties;
    /** 本次心跳统一时间来源。 */
    private final Clock clock;

    /**
     * 处理一次观看心跳（区分起播与后续心跳）。
     *
     * @param vid 视频公开业务短码
     * @param userId 已鉴权登录用户 ID
     * @param command 心跳命令
     * @return 心跳处理结果
     * @throws IllegalArgumentException 视频编码或用户 ID 为空
     * @throws WatchSessionActiveException 起播时仍存在存活活跃会话 (409)
     * @throws WatchSessionExpiredException 起播重试或后续心跳命中已超时过期会话 (409)
     * @throws WatchSessionInvalidException 后续心跳会话 ID 无效或不属于当前用户/视频 (409)
     */
    @Transactional(rollbackFor = Exception.class)
    public WatchHeartbeatOutcome processHeartbeat(String vid, String userId, WatchHeartbeatCommand command) {
        String cleanVid = vid != null ? vid.trim() : "";
        String cleanUserId = userId != null ? userId.trim() : "";
        if (cleanVid.isBlank() || cleanUserId.isBlank()) {
            throw new IllegalArgumentException("视频编码与用户ID不能为空");
        }

        WatchHeartbeatCommand cmd = command != null
                ? command
                : new WatchHeartbeatCommand(null, 0L, 0, 0, null);

        // 步骤 1：以行级排他锁取得观看进度，作为同一用户同一视频心跳的串行化入口
        // 入口仅取一次 UTC 时间并传给建档、计数和事件，确保事务内判定时间基准一致
        Instant instant = clock.instant();
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        WatchProgress progress = lockOrCreateProgress(cleanUserId, cleanVid, now);

        // 步骤 2：读取本地视频元数据快照，区分为内容准入 (isPublished) 与时长可用 (isUsable)
        VideoSnapshot snapshot = videoSnapshotRepository.findByVid(cleanVid).orElse(null);
        int durationSnapshot = (snapshot != null && snapshot.isUsable()) ? snapshot.getDuration() : 0;
        int safePosition = resolveSafePosition(cmd.position(), durationSnapshot);

        // 步骤 3：根据命令类型进入起播或后续心跳分支
        if (cmd.isStartPlay()) {
            return processStartPlay(cleanUserId, cleanVid, cmd, progress, snapshot, durationSnapshot, safePosition, now);
        } else {
            return processSubsequentHeartbeat(cleanUserId, cleanVid, cmd, progress, durationSnapshot, safePosition, now);
        }
    }

    /**
     * 处理起播请求（创建新会话或返回重试回执，并执行起播计数决策）。
     */
    private WatchHeartbeatOutcome processStartPlay(String userId, String vid, WatchHeartbeatCommand cmd,
                                                   WatchProgress progress, VideoSnapshot snapshot,
                                                   int durationSnapshot, int safePosition, LocalDateTime now) {
        String startKey = cmd.startRequestKey();

        // 步骤 1：按起播请求幂等键查本用户本视频的历史会话（防重试）
        if (startKey != null && !startKey.isBlank()) {
            Optional<WatchSession> existingByStartKey = sessionRepository.findByStartRequestKey(userId, vid, startKey);
            if (existingByStartKey.isPresent()) {
                WatchSession session = existingByStartKey.get();
                // 历史会话若已超时过期或已被关闭，返回 409 WATCH_SESSION_EXPIRED
                if (session.getClosedAt() != null || session.isExpired(now, properties.getSessionTimeout())) {
                    log.debug("起播重试命中已过期历史会话: userId={}, vid={}, sessionId={}, startKey={}",
                            userId, vid, session.getSessionId(), startKey);
                    throw new WatchSessionExpiredException("起播请求已超时失效，请重新发起播放");
                }
                // 未过期则幂等返回该会话只读回执，不刷新活跃时间、不重复计数
                log.debug("起播请求幂等重试命中活跃会话，返回只读回执: userId={}, vid={}, sessionId={}, startKey={}",
                        userId, vid, session.getSessionId(), startKey);
                Set<WatchEventType> claimedTypes = loadClaimedTypes(userId, vid, session.getSessionId());
                return buildOutcome(progress, session, session.getDurationSnapshot(),
                        session.isViewCounted(),
                        claimedTypes.contains(WatchEventType.WATCH_COMPLETED),
                        true);
            }
        }

        // 步骤 2：起播键不存在时，检查当前是否已有未超时活跃会话
        String activeSessionId = progress.getActiveSessionId();
        if (activeSessionId != null && !activeSessionId.isBlank()) {
            WatchSession active = sessionRepository.findById(activeSessionId).orElse(null);
            if (active != null && active.getClosedAt() == null && !active.isExpired(now, properties.getSessionTimeout())) {
                // 当前会话仍活跃，返回 409 WATCH_SESSION_ACTIVE 要求播放器恢复现有会话
                log.debug("起播请求到达但当前仍有存活活跃会话，要求恢复: userId={}, vid={}, activeSessionId={}",
                        userId, vid, active.getSessionId());
                throw new WatchSessionActiveException(active.getSessionId(), active.getLastSequence());
            } else if (active != null && active.getClosedAt() == null) {
                // 活跃会话已超时，予以关闭
                active.close(now);
                sessionRepository.close(active.getSessionId(), now);
                log.debug("历史活跃会话超时关闭: userId={}, vid={}, closedSessionId={}", userId, vid, active.getSessionId());
            }
        }

        // 步骤 3：创建新会话并固定门槛与起播幂等键
        int threshold = WatchQualificationPolicy.resolveQualificationThreshold(
                durationSnapshot, properties.getValidPlayThreshold(), properties.getQualificationRatio());
        WatchSession newSession = WatchSession.open(userId, vid, startKey, durationSnapshot, threshold, safePosition, now);

        // 步骤 4：起播计数决策：内容已正式发布 (PUBLISHED) && 播放量冷却在 now 已结束
        boolean contentUsable = (snapshot != null && snapshot.isPublished());
        boolean cooldownElapsed = WatchQualificationPolicy.isCooldownElapsed(
                progress.getLastViewClaimedAt(), now, properties.getRepeatWindow());
        if (contentUsable && cooldownElapsed) {
            // 在同一业务事务内写入播放量增量，并更新会话的 viewCountedAt 与进度的 lastViewClaimedAt
            counterDeltaRepository.incrementViewCount(vid, "watch_session:" + newSession.getSessionId(), 1L, now);
            newSession.markViewCounted(now);
            progress.markViewClaimed(now);
            progressRepository.markViewClaimed(progress.getId(), now);
            log.info("起播开启新会话并计入播放量: userId={}, vid={}, sessionId={}", userId, vid, newSession.getSessionId());
        } else {
            log.debug("起播开启新会话但不计入播放量: userId={}, vid={}, sessionId={}, contentUsable={}, cooldownElapsed={}",
                    userId, vid, newSession.getSessionId(), contentUsable, cooldownElapsed);
        }

        // 步骤 5：保存新会话并挂接断点到进度
        sessionRepository.insert(newSession);
        progress.attachSession(newSession.getSessionId());
        progress.recordHeartbeat(safePosition, 0, now);
        progressRepository.updateHeartbeat(progress);

        // 起播不生成合格观看或完播事件
        return buildOutcome(progress, newSession, durationSnapshot, newSession.isViewCounted(), false, false);
    }

    /**
     * 处理后续心跳请求（安全累计有效时长，独立判定合格观看与完播事件，绝不触碰播放量计数）。
     */
    private WatchHeartbeatOutcome processSubsequentHeartbeat(String userId, String vid, WatchHeartbeatCommand cmd,
                                                             WatchProgress progress, int durationSnapshot,
                                                             int safePosition, LocalDateTime now) {
        String clientSessionId = cmd.sessionId();

        // 步骤 1：验证 sessionId 属于当前用户与当前视频，防止伪造会话
        WatchSession session = sessionRepository.findById(clientSessionId).orElse(null);
        if (session == null || !userId.equals(session.getUserId()) || !vid.equals(session.getVid())) {
            log.warn("心跳上报无效或不属于当前用户/视频的会话 ID: userId={}, vid={}, clientSessionId={}",
                    userId, vid, clientSessionId);
            throw new WatchSessionInvalidException("观看会话无效或不存在");
        }

        // 步骤 2：序号检查：重复或较小序号只返回只读回执，不累计时长、不更新断点、不重复发事件
        if (session.isReplayOrStale(cmd.sequence())) {
            log.debug("心跳序号重复或过期，幂等返回当前状态: userId={}, vid={}, sessionId={}, sequence={}, lastSequence={}",
                    userId, vid, session.getSessionId(), cmd.sequence(), session.getLastSequence());
            Set<WatchEventType> claimedTypes = loadClaimedTypes(userId, vid, session.getSessionId());
            return buildOutcome(progress, session, session.getDurationSnapshot(),
                    session.isViewCounted(),
                    claimedTypes.contains(WatchEventType.WATCH_COMPLETED),
                    true);
        }

        // 步骤 3：会话超时与状态检查
        // 若会话已关闭、超时、或已不再是当前活跃会话，抛出 409 WATCH_SESSION_EXPIRED
        boolean isCurrentActive = session.getSessionId().equals(progress.getActiveSessionId());
        boolean isExpired = session.isExpired(now, properties.getSessionTimeout());
        if (session.getClosedAt() != null || isExpired || !isCurrentActive) {
            if (session.getClosedAt() == null && isExpired) {
                session.close(now);
                sessionRepository.close(session.getSessionId(), now);
            }
            log.debug("后续心跳命中的会话已关闭或过期: userId={}, vid={}, sessionId={}", userId, vid, session.getSessionId());
            throw new WatchSessionExpiredException("观看会话已超时过期，请重新起播");
        }

        // 步骤 4：把客户端增量换算为服务端认可的有效观看增量
        int creditedDelta = WatchCreditValidator.resolveCreditedDelta(
                cmd.deltaDuration(),
                session.getLastHeartbeatAt(),
                now,
                properties.getMaxHeartbeatDelta(),
                properties.getHeartbeatCreditTolerance(),
                session.getDurationSnapshot(),
                session.getCreditedDuration());
        if (WatchCreditValidator.isSuspiciousForwardJump(session.getLastPosition(), safePosition,
                creditedDelta, properties.getHeartbeatCreditTolerance())) {
            log.debug("心跳断点前跳幅度超过认可增量，仅更新断点不计入时长: userId={}, vid={}, previous={}, current={}, credited={}",
                    userId, vid, session.getLastPosition(), safePosition, creditedDelta);
        }

        // 步骤 5：累计到会话与进度
        session.recordHeartbeat(creditedDelta, safePosition, cmd.sequence(), now);
        session.refreshQualification();
        sessionRepository.updateHeartbeat(session);

        progress.recordHeartbeat(safePosition, creditedDelta, now);
        progressRepository.updateHeartbeat(progress);

        // 步骤 6：合格观看与完播事件独立判定与凭据抢占（绝不更新播放量增量或冷却时间）
        Set<WatchEventType> claimedTypes = loadClaimedTypes(userId, vid, session.getSessionId());
        claimQualifiedWatchIfEligible(userId, vid, session,
                claimedTypes.contains(WatchEventType.WATCH_VIEW_QUALIFIED), now);
        boolean completed = claimCompletionIfEligible(userId, vid, session, safePosition,
                claimedTypes.contains(WatchEventType.WATCH_COMPLETED), now);

        return buildOutcome(progress, session, session.getDurationSnapshot(),
                session.isViewCounted(), completed, false);
    }

    /**
     * 以行级排他锁取得观看进度；记录不存在时创建，并发首次心跳由唯一键兜底后退化为锁定已存在行。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param now 当前业务时间
     * @return 已持锁的观看进度实体
     */
    private WatchProgress lockOrCreateProgress(String userId, String vid, LocalDateTime now) {
        Optional<WatchProgress> existing = progressRepository.lockByUserAndVid(userId, vid);
        if (existing.isPresent()) {
            return existing.get();
        }

        WatchProgress created = WatchProgress.create(userId, vid, now);
        try {
            progressRepository.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            log.debug("并发首次心跳触发唯一键冲突，转为锁定已存在记录: userId={}, vid={}", userId, vid);
            return progressRepository.lockByUserAndVid(userId, vid)
                    .orElseThrow(() -> new IllegalStateException(
                            "并发首次心跳未能锁定观看进度记录: userId=" + userId + ", vid=" + vid));
        }
    }

    /**
     * 在会话达到合格观看门槛时抢占凭据并发出领域事件。
     *
     * <p>与播放量完全解耦：不检查冷却窗口、不写入播放量增量、不刷新冷却时间。</p>
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param session 当前会话
     * @param alreadyClaimed 本会话此前是否已抢占过合格观看凭据
     * @param now 当前业务时间
     * @return 本会话是否已达成合格观看（含此前已达成或本次刚达成）
     */
    private boolean claimQualifiedWatchIfEligible(String userId, String vid, WatchSession session,
                                                  boolean alreadyClaimed, LocalDateTime now) {
        if (alreadyClaimed) {
            return true;
        }
        if (!session.isQualified()) {
            return false;
        }

        WatchEventClaim claim = WatchEventClaim.create(userId, vid, session.getSessionId(),
                WatchEventType.WATCH_VIEW_QUALIFIED, now);
        if (!claimRepository.tryClaim(claim)) {
            return false;
        }

        String outboxEventId = eventPublisher.publishVideoAction(VideoActionPayload.watchViewQualified(
                userId, vid, session.getSessionId(), session.getCreditedDuration(), session.getDurationSnapshot()), now.toInstant(ZoneOffset.UTC));
        claimRepository.attachOutboxEventId(claim.getId(), outboxEventId);
        log.info("观看会话达成合格观看门槛并发出合格事件: userId={}, vid={}, sessionId={}, creditedDuration={}",
                userId, vid, session.getSessionId(), session.getCreditedDuration());
        return true;
    }

    /**
     * 在同时满足位置与有效时长双 90% 条件时抢占完播凭据。
     *
     * <p>完播不增加公开播放量，只产生对外完播事件。</p>
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param session 当前会话
     * @param position 本次播放位置 (秒)
     * @param alreadyCompleted 本会话此前是否已计入过完播
     * @param now 当前业务时间
     * @return 本会话是否已计入完播
     */
    private boolean claimCompletionIfEligible(String userId, String vid, WatchSession session,
                                              int position, boolean alreadyCompleted, LocalDateTime now) {
        if (alreadyCompleted) {
            return true;
        }
        if (!WatchQualificationPolicy.isCompleted(position, session.getCreditedDuration(),
                session.getDurationSnapshot(), properties.getCompletionRatio())) {
            return false;
        }

        WatchEventClaim claim = WatchEventClaim.create(userId, vid, session.getSessionId(),
                WatchEventType.WATCH_COMPLETED, now);
        if (!claimRepository.tryClaim(claim)) {
            return false;
        }

        String outboxEventId = eventPublisher.publishVideoAction(VideoActionPayload.watchCompleted(
                userId, vid, session.getSessionId(), session.getCreditedDuration(), session.getDurationSnapshot()), now.toInstant(ZoneOffset.UTC));
        claimRepository.attachOutboxEventId(claim.getId(), outboxEventId);
        log.info("观看会话达成完播并记为一次完播: userId={}, vid={}, sessionId={}, position={}, creditedDuration={}",
                userId, vid, session.getSessionId(), position, session.getCreditedDuration());
        return true;
    }

    /**
     * 读取本会话已抢占的凭据类型集合。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param sessionId 会话 ID
     * @return 凭据类型集合
     */
    private Set<WatchEventType> loadClaimedTypes(String userId, String vid, String sessionId) {
        return claimRepository.findClaimedTypes(userId, vid, sessionId);
    }

    /**
     * 组装心跳结果。
     *
     * @param progress 观看进度
     * @param session 当前会话
     * @param durationSnapshot 视频时长快照 (秒)
     * @param viewCounted 本会话是否已计入播放量
     * @param completed 本会话是否已计入完播
     * @param duplicateOrStaleRequest 是否为重复或乱序请求
     * @return 心跳结果
     */
    private WatchHeartbeatOutcome buildOutcome(WatchProgress progress, WatchSession session,
                                               int durationSnapshot, boolean viewCounted, boolean completed,
                                               boolean duplicateOrStaleRequest) {
        return new WatchHeartbeatOutcome(
                progress.getVid(),
                session.getSessionId(),
                session.getLastSequence(),
                session.getLastPosition(),
                progress.getWatchedDuration(),
                session.getCreditedDuration(),
                durationSnapshot,
                session.getQualificationThreshold(),
                session.isQualified(),
                viewCounted,
                completed,
                duplicateOrStaleRequest);
    }

    /**
     * 把播放位置规整为非负值，并在有可用时长快照时截断到视频时长以内。
     *
     * @param position 客户端上报位置 (秒)
     * @param durationSnapshot 视频时长快照 (秒)
     * @return 规整后的播放位置
     */
    private int resolveSafePosition(int position, int durationSnapshot) {
        int safe = Math.max(0, position);
        if (durationSnapshot > 0) {
            safe = Math.min(safe, durationSnapshot);
        }
        return safe;
    }
}
