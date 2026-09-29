package com.calles.platform.interaction.application.query;

import com.calles.platform.interaction.application.like.LikeApplicationService;
import com.calles.platform.interaction.application.star.StarApplicationService;
import com.calles.platform.interaction.application.watch.WatchProgressApplicationService;
import com.calles.platform.interaction.application.watch.WatchProgressView;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import com.calles.platform.interaction.application.InteractionTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.share.InteractionShareRecord;
import com.calles.platform.interaction.exception.InteractionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 互动服务聚合查询应用服务。
 *
 * <p>为播放页提供聚合操作快照，为推荐与视频列表提供公开计数与批量统计装配。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionQueryApplicationService {

    private final LikeApplicationService likeService;
    private final StarApplicationService starService;
    private final WatchProgressApplicationService watchProgressService;
    private final VideoCounterRepository counterRepository;
    private final CounterDeltaRepository counterDeltaRepository;
    private final com.calles.platform.interaction.domain.repository.InteractionShareRecordRepository shareRecordRepository;
    private final com.calles.platform.interaction.application.event.InteractionEventPublisher eventPublisher;
    /** 分享及缺失快照的统一 UTC 时间来源。 */
    private final Clock clock;

    /**
     * 用户在指定视频上的互动状态快照（播放页一站式聚合响应）。
     */
    @Getter
    @Builder
    public static class UserInteractionState {
        private String vid;
        private boolean liked;
        private boolean starred;
        private int lastWatchPosition;
        private boolean completed;
    }

    /**
     * 获取当前登录用户针对特定视频的互动快照。
     *
     * @param vid 视频公开短码
     * @param userId 登录用户 ID
     * @return 聚合状态对象
     */
    public UserInteractionState getMyState(String vid, String userId) {
        if (userId == null || userId.isBlank()) {
            return UserInteractionState.builder()
                    .vid(vid)
                    .liked(false)
                    .starred(false)
                    .lastWatchPosition(0)
                    .completed(false)
                    .build();
        }

        // 步骤 1: 并行或就近读取点赞与收藏状态
        boolean isLiked = likeService.isLiked(vid, userId);
        boolean isStarred = starService.isStarred(vid, userId);

        // 步骤 2: 读取观看断点进度
        WatchProgressView watchProgress = watchProgressService.getProgress(vid, userId);
        int lastPos = watchProgress.lastPosition();
        boolean isCompleted = watchProgress.completed();

        return UserInteractionState.builder()
                .vid(vid)
                .liked(isLiked)
                .starred(isStarred)
                .lastWatchPosition(lastPos)
                .completed(isCompleted)
                .build();
    }

    /**
     * 获取单条视频的公开互动统计。
     *
     * @param vid 视频编码
     * @return 统计计数实体 (若无记录则返回全 0 对象)
     */
    public VideoCounter getVideoStat(String vid) {
        return counterRepository.findByVid(vid)
                .orElseGet(() -> VideoCounter.createDefault(vid, InteractionTime.utcNow(clock)));
    }

    /**
     * 批量获取多个视频的公开互动统计。
     *
     * @param vids 视频编码列表
     * @return 视频编码到统计实体的映射
     */
    public Map<String, VideoCounter> getBatchVideoStats(Collection<String> vids) {
        if (vids == null || vids.isEmpty()) {
            return Map.of();
        }
        List<VideoCounter> list = counterRepository.findByVids(vids);
        Map<String, VideoCounter> map = list.stream()
                .collect(Collectors.toMap(VideoCounter::getVid, Function.identity(), (a, b) -> a));

        LocalDateTime now = InteractionTime.utcNow(clock);
        // 补齐缺失项为默认 0 计数实体
        for (String vid : vids) {
            map.putIfAbsent(vid, VideoCounter.createDefault(vid, now));
        }
        return map;
    }

    /**
     * 记录并自增视频分享计数（基于请求幂等键持久化防重）。
     *
     * <p>根据操作用户与客户端提供的 {@code idempotencyKey} 联合防重：
     * 1. 命中相同用户针对相同视频的已有记录时，直接幂等返回，不重复递增计数与发布事件；
     * 2. 命中相同用户针对不同视频的已有记录时，视为幂等键复用冲突，抛出 409 Conflict 领域异常；
     * 3. 首次请求时同事务完成幂等记录持久化、增量流水写入与 Outbox 事件投递；
     * 4. 针对高并发请求，由数据库联合唯一索引拦截并发重复写入，捕获唯一键异常后回退二次判定幂等状态。</p>
     *
     * @param vid 视频业务公开短码
     * @param userId 操作用户 ID
     * @param idempotencyKey 客户端请求幂等键
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordShare(String vid, String userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("分享请求必须携带有效的 Idempotency-Key");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("分享请求必须指定有效的操作用户");
        }
        if (vid == null || vid.isBlank()) {
            throw new IllegalArgumentException("分享请求必须指定有效的视频编码");
        }

        // 幂等记录、增量与领域事件共享一次时间读取。
        Instant now = clock.instant();
        LocalDateTime localNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        String safeKey = idempotencyKey.trim();
        String safeUserId = userId.trim();
        String safeVid = vid.trim();

        // 步骤 1: 检索用户维度的既有幂等记录
        Optional<InteractionShareRecord> existing =
                shareRecordRepository.findByUserIdAndIdempotencyKey(safeUserId, safeKey);
        if (existing.isPresent()) {
            InteractionShareRecord record = existing.get();
            if (!record.getVid().equals(safeVid)) {
                throw new InteractionException(HttpStatus.CONFLICT, "幂等键已被用于其他分享请求");
            }
            log.info("检测到重复的分享请求 (幂等命中): idempotencyKey={}, userId={}, vid={}", safeKey, safeUserId, safeVid);
            return;
        }

        // 步骤 2: 首次请求，持久化幂等记录、自增计数，并在同一事务内写入 Outbox
        // 若并发请求同时到达，由唯一键 uk_share_user_idempotency 拦截，回退判断幂等状态
        InteractionShareRecord newRecord = InteractionShareRecord.create(safeKey, safeUserId, safeVid, now);
        try {
            shareRecordRepository.save(newRecord);
        } catch (DuplicateKeyException e) {
            log.warn("并发分享请求触发唯一键冲突，采用当前读回退检索幂等记录: idempotencyKey={}, userId={}, vid={}", safeKey, safeUserId, safeVid);
            // 步骤 2.1: 采用当前读 (FOR UPDATE) 穿透 MySQL REPEATABLE READ 快照，实时获取胜出事务已提交的记录
            InteractionShareRecord concurrentRecord = shareRecordRepository.findByUserIdAndIdempotencyKeyForUpdate(safeUserId, safeKey)
                    .orElseThrow(() -> e);
            if (!concurrentRecord.getVid().equals(safeVid)) {
                throw new InteractionException(HttpStatus.CONFLICT, "幂等键已被用于其他分享请求");
            }
            log.info("并发分享请求当前读幂等命中: idempotencyKey={}, userId={}, vid={}", safeKey, safeUserId, safeVid);
            return;
        }

        String sourceId = "share:" + safeUserId + ":" + safeKey;
        counterDeltaRepository.incrementShareCount(safeVid, sourceId, 1L, localNow);
        eventPublisher.publishVideoAction(VideoActionPayload.share(safeUserId, safeVid), now);
        log.info("用户 [{}] 成功分享视频 [{}]，幂等键 [{}]，写入 Outbox", safeUserId, safeVid, safeKey);
    }
}
