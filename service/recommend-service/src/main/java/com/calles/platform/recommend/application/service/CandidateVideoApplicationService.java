package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.interfaces.messaging.event.VideoPublishedMessage;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 推荐候选池视频管理应用服务 (CandidateVideoApplicationService)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：负责视频正式进入推荐候选库存池与生命周期状态变迁的应用层编排；</li>
 *   <li><b>幂等与并发</b>：多节点并发或 MQ 重复投递场景下，利用数据库唯一索引 (uk_rcv_video_id) 与 insertIgnore 保障强幂等；</li>
 *   <li><b>状态治理</b>：严格响应上游领域发布、下架与封禁事件，确保推荐池数据与全站合规状态对齐。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateVideoApplicationService {

    private final CandidateVideoRepository candidateVideoRepository;

    /**
     * 处理视频正式发布入池用例 (消费 content.video.published 事件)。
     *
     * @param message 包含发布视频核心标识、作者及标签快照的领域消息
     */
    @Transactional
    public void handlePublished(VideoPublishedMessage message) {
        if (message == null || message.videoId() == null || message.videoId().isBlank()) {
            log.warn("收到无效的视频发布入池事件，跳过处理: message={}", message);
            return;
        }

        String videoId = message.videoId().trim();
        String vid = message.vid();
        String authorId = message.authorId();

        // 步骤 1：前置检查候选池是否已存在该视频记录
        Optional<CandidateVideo> existingOpt = candidateVideoRepository.findByVideoId(videoId);
        if (existingOpt.isPresent()) {
            CandidateVideo existing = existingOpt.get();
            // 已存在且状态为 ACTIVE，直接幂等返回
            if (existing.isRecommendable()) {
                log.debug("视频候选已存在且处于 ACTIVE 推荐态，无需重复入池: videoId={}", videoId);
                return;
            }
            // 若原先是 OFFLINE 状态后重新上架，重新激活为 ACTIVE 推荐态
            candidateVideoRepository.updateStatusByVideoId(videoId, CandidateStatus.ACTIVE);
            log.info("已将重上架视频重新激活入推荐池: videoId={}, vid={}", videoId, vid);
            return;
        }

        // 步骤 2：构造全新的候选池领域实体 (状态为 ACTIVE)
        String id = UUID.randomUUID().toString().replace("-", "");
        CandidateVideo candidate = CandidateVideo.createPublished(
                id,
                videoId,
                vid,
                authorId,
                message.domainTagIds(),
                message.topicTagIds(),
                message.publishedAt()
        );

        // 步骤 3：尝试持久化落库；利用底层 insertIgnore 与 DuplicateKeyException 兜底并发冲突
        try {
            int rows = candidateVideoRepository.insert(candidate);
            if (rows > 0) {
                log.info("视频成功进入推荐候选库存池: videoId={}, vid={}, authorId={}", videoId, vid, authorId);
            } else {
                log.info("视频候选已被并发插入，安全忽略: videoId={}", videoId);
            }
        } catch (DuplicateKeyException e) {
            log.info("捕获并发重复发布入池异常，幂等安全放行: videoId={}", videoId);
        }
    }

    /**
     * 处理创作者主动下线用例 (消费 content.video.offlined 事件)。
     *
     * @param videoId 视频内部 ID
     * @param vid 视频公开短码
     * @param reason 下线原因
     */
    @Transactional
    public void handleOfflined(String videoId, String vid, String reason) {
        if (videoId == null || videoId.isBlank()) {
            return;
        }
        int rows = candidateVideoRepository.updateStatusByVideoId(videoId.trim(), CandidateStatus.OFFLINE);
        log.info("已将视频从推荐候选池下线 (OFFLINE): videoId={}, vid={}, rows={}, reason={}", videoId, vid, rows, reason);
    }

    /**
     * 处理平台合规封禁熔断用例 (消费 content.video.banned 事件)。
     *
     * @param videoId 视频内部 ID
     * @param vid 视频公开短码
     * @param reason 封禁原因
     */
    @Transactional
    public void handleBanned(String videoId, String vid, String reason) {
        if (videoId == null || videoId.isBlank()) {
            return;
        }
        int rows = candidateVideoRepository.updateStatusByVideoId(videoId.trim(), CandidateStatus.BANNED);
        log.warn("已将视频从推荐候选池合规熔断封禁 (BANNED): videoId={}, vid={}, rows={}, reason={}", videoId, vid, rows, reason);
    }
}
