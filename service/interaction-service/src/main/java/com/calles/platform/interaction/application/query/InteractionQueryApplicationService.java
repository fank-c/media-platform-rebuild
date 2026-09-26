package com.calles.platform.interaction.application.query;

import com.calles.platform.interaction.application.like.LikeApplicationService;
import com.calles.platform.interaction.application.star.StarApplicationService;
import com.calles.platform.interaction.application.watch.WatchHeartbeatApplicationService;
import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.domain.model.watch.WatchHistory;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final WatchHeartbeatApplicationService watchService;
    private final VideoCounterRepository counterRepository;
    private final CounterDeltaRepository counterDeltaRepository;
    private final com.calles.platform.interaction.domain.repository.InteractionShareRecordRepository shareRecordRepository;
    private final com.calles.platform.interaction.application.event.InteractionEventPublisher eventPublisher;

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

        // 步骤 2: 读取观看历史断点进度
        Optional<WatchHistory> watchOpt = watchService.getWatchProgress(vid, userId);
        int lastPos = watchOpt.map(WatchHistory::getLastPosition).orElse(0);
        boolean isCompleted = watchOpt.map(WatchHistory::isCompleted).orElse(false);

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
                .orElseGet(() -> VideoCounter.createDefault(vid));
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

        // 补齐缺失项为默认 0 计数实体
        for (String vid : vids) {
            map.putIfAbsent(vid, VideoCounter.createDefault(vid));
        }
        return map;
    }

    /**
     * 记录并自增视频分享计数（基于请求幂等键持久化防重）。
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

        // 步骤 1: 检索既有幂等记录
        Optional<com.calles.platform.interaction.domain.model.share.InteractionShareRecord> existing =
                shareRecordRepository.findByIdempotencyKey(idempotencyKey.trim());
        if (existing.isPresent()) {
            com.calles.platform.interaction.domain.model.share.InteractionShareRecord record = existing.get();
            if (!record.getUserId().equals(userId) || !record.getVid().equals(vid)) {
                throw new IllegalStateException("幂等键已被不同的分享请求使用");
            }
            log.info("检测到重复的分享请求 (幂等命中): idempotencyKey={}, userId={}, vid={}", idempotencyKey, userId, vid);
            return;
        }

        // 步骤 2: 首次请求，持久化幂等记录、自增计数，并在同一事务内写入 Outbox
        com.calles.platform.interaction.domain.model.share.InteractionShareRecord newRecord =
                com.calles.platform.interaction.domain.model.share.InteractionShareRecord.create(idempotencyKey.trim(), userId, vid);
        shareRecordRepository.save(newRecord);
        counterDeltaRepository.incrementShareCount(vid, idempotencyKey.trim(), 1L);
        eventPublisher.publishVideoAction(com.calles.platform.interaction.domain.model.event.VideoActionPayload.share(userId, vid));
        log.info("用户 [{}] 成功分享视频 [{}]，幂等键 [{}]，写入 Outbox", userId, vid, idempotencyKey);
    }
}
