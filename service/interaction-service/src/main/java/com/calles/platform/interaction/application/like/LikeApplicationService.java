package com.calles.platform.interaction.application.like;

import com.calles.platform.interaction.application.event.InteractionEventPublisher;
import com.calles.platform.interaction.domain.model.event.VideoActionPayload;
import com.calles.platform.interaction.domain.model.like.VideoLike;
import com.calles.platform.interaction.domain.repository.CounterDeltaRepository;
import com.calles.platform.interaction.domain.repository.VideoLikeRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 视频点赞业务应用服务。
 *
 * <p>维护点赞状态反转与计数器增量追加，并在真实点赞状态变化时同事务写入 Outbox 领域事件与待汇总计数增量。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeApplicationService {

    private final VideoLikeRepository likeRepository;
    private final CounterDeltaRepository counterDeltaRepository;
    private final InteractionEventPublisher eventPublisher;

    /**
     * 点赞视频（严格幂等）。
     *
     * @param vid 视频公开短码
     * @param userId 用户账号 ID
     * @return 当前是否点赞成功 (恒为 true)
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean likeVideo(String vid, String userId) {
        // 步骤 1: 检索既有历史点赞记录
        Optional<VideoLike> opt = likeRepository.findByUserAndVid(userId, vid);

        if (opt.isEmpty()) {
            // 步骤 2: 首次点赞，持久化新记录并递增计数增量，同事务写 Outbox
            VideoLike newLike = VideoLike.create(vid, userId);
            likeRepository.save(newLike);
            counterDeltaRepository.adjustLikeCount(vid, "like:" + newLike.getId() + ":v" + newLike.getVersion(), 1L);
            eventPublisher.publishVideoAction(VideoActionPayload.like(userId, vid));
            log.info("用户 [{}] 首次点赞视频 [{}]，写入 Outbox 与计数增量 (version={})", userId, vid, newLike.getVersion());
            return true;
        }

        VideoLike existing = opt.get();
        if (existing.reactivate()) {
            // 步骤 3: 从已取消状态重新激活点赞，递增计数增量，同事务写 Outbox
            likeRepository.update(existing);
            counterDeltaRepository.adjustLikeCount(vid, "like:" + existing.getId() + ":v" + existing.getVersion(), 1L);
            eventPublisher.publishVideoAction(VideoActionPayload.like(userId, vid));
            log.info("用户 [{}] 重新点赞视频 [{}]，写入 Outbox 与计数增量 (version={})", userId, vid, existing.getVersion());
        } else {
            // 步骤 4: 重复点赞幂等忽略，不写 Outbox 与增量
            log.debug("用户 [{}] 重复点赞视频 [{}]，幂等忽略", userId, vid);
        }
        return true;
    }

    /**
     * 取消点赞视频（严格幂等）。
     *
     * @param vid 视频公开短码
     * @param userId 用户账号 ID
     * @return 当前点赞状态 (恒为 false)
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean unlikeVideo(String vid, String userId) {
        // 步骤 1: 检索既有点赞记录
        Optional<VideoLike> opt = likeRepository.findByUserAndVid(userId, vid);

        if (opt.isPresent()) {
            VideoLike existing = opt.get();
            if (existing.cancel()) {
                // 步骤 2: 状态由有效反转为取消，扣减计数增量，同事务写 Outbox
                likeRepository.update(existing);
                counterDeltaRepository.adjustLikeCount(vid, "like:" + existing.getId() + ":v" + existing.getVersion(), -1L);
                eventPublisher.publishVideoAction(VideoActionPayload.unlike(userId, vid));
                log.info("用户 [{}] 取消点赞视频 [{}]，写入 Outbox 与计数增量 (version={})", userId, vid, existing.getVersion());
            }
        }
        return false;
    }

    /**
     * 判断指定用户是否已点赞某视频。
     *
     * @param vid 视频编码
     * @param userId 用户 ID
     * @return true 若已点赞
     */
    public boolean isLiked(String vid, String userId) {
        return likeRepository.isLiked(userId, vid);
    }
}
