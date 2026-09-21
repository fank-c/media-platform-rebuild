package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.config.RecommendEmbeddingProperties;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.VideoVector;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.model.feedback.FeedbackActionType;
import com.calles.platform.recommend.domain.model.feedback.FeedbackLog;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.model.profile.UserVector;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.FeedbackLogRepository;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import com.calles.platform.recommend.domain.repository.UserProfileRepository;
import com.calles.platform.recommend.domain.repository.VideoVectorRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 行为反馈流水与画像演进应用服务 (FeedbackApplicationService)。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>不可篡改流水存证</b>：将客户端上报的每一次交互行为如实记入 {@code recommend_feedback_log}；</li>
 *   <li><b>驱动画像正向演进</b>：对完播/深读消费通过 EMA 增量微调用户检索向量并累加细主题偏好分；</li>
 *   <li><b>疲劳负向抑制</b>：对快速滑过累积粗领域曝光未消费，触发后续打折；</li>
 *   <li><b>明确负反馈联动</b>：对不感兴趣/投诉行为自动沉淀为明确屏蔽黑名单。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackApplicationService {

    private final FeedbackLogRepository feedbackLogRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserBlockRepository userBlockRepository;
    private final CandidateVideoRepository candidateVideoRepository;
    private final VideoVectorRepository videoVectorRepository;
    private final RecommendEmbeddingProperties embeddingProperties;
    private final ObjectMapper objectMapper;

    /**
     * 处理单笔客户端交互行为反馈。
     *
     * @param userId 行为发生用户ID (可为 null，代表游客)
     * @param vid 交互视频公开短码
     * @param actionType 行为动作类型
     * @param playDuration 播放时长 (秒)
     * @param videoDuration 视频总时长 (秒)
     * @param reason 负反馈原因
     * @param traceId 全链路追踪ID
     * @param occurredAt 行为真实发生时间
     */
    @Transactional(rollbackFor = Exception.class)
    public void recordFeedback(String userId, String vid, FeedbackActionType actionType,
                               int playDuration, int videoDuration, String reason,
                               String traceId, LocalDateTime occurredAt) {
        if (vid == null || vid.isBlank() || actionType == null) {
            return;
        }

        // 步骤 1：查询当前物料快照 (领域、主题、作者)
        Optional<CandidateVideo> candidateOpt = candidateVideoRepository.findByVid(vid.trim());
        String domainTagIds = candidateOpt.map(CandidateVideo::getDomainTagIds).orElse(null);
        String topicTagIds = candidateOpt.map(CandidateVideo::getTopicTagIds).orElse(null);
        String authorId = candidateOpt.map(CandidateVideo::getAuthorId).orElse(null);

        // 步骤 2：写入不可篡改的行为事实账本
        String effectiveUserId = (userId != null && !userId.isBlank()) ? userId.trim() : "anonymous";
        FeedbackLog logEntity = FeedbackLog.record(
                effectiveUserId,
                vid.trim(),
                actionType,
                playDuration,
                videoDuration,
                domainTagIds,
                topicTagIds,
                authorId,
                traceId,
                occurredAt != null ? occurredAt : LocalDateTime.now()
        );
        feedbackLogRepository.save(logEntity);

        // 步骤 3：若为游客行为，不驱动画像演进
        if ("anonymous".equals(effectiveUserId)) {
            return;
        }

        // 步骤 4：加载或初始化用户画像
        UserProfile userProfile = userProfileRepository.findByUserId(effectiveUserId)
                .orElseGet(() -> UserProfile.initialize(effectiveUserId, embeddingProperties.getDimension()));

        // 步骤 5：按行为类型分流驱动模型演进
        handleProfileUpdate(userProfile, effectiveUserId, vid, actionType, playDuration, videoDuration,
                domainTagIds, topicTagIds, authorId, reason);
    }

    /**
     * 根据具体行为类型执行画像微调与黑名单沉淀。
     */
    private void handleProfileUpdate(UserProfile userProfile, String userId, String vid,
                                     FeedbackActionType actionType, int playDuration, int videoDuration,
                                     String domainTagIds, String topicTagIds, String authorId, String reason) {
        double playRatio = videoDuration > 0 ? (double) playDuration / videoDuration : 0.0;
        List<String> topicList = parseCommaList(topicTagIds);
        String primaryDomain = extractFirstTag(domainTagIds);

        switch (actionType) {
            case PLAY:
                // 门禁：完播率 >= 30% 或实际播放时长 >= 10 秒视为有效正向消费
                if (playRatio >= 0.3 || playDuration >= 10) {
                    List<Float> vector = fetchVideoVector(vid);
                    userProfile.recordPositiveConsumption(vid, vector, topicList, primaryDomain, UserVector.DEFAULT_ALPHA);
                    userProfileRepository.saveOrUpdate(userProfile);
                    log.debug("用户画像正向消费推进成功: userId={}, vid={}", userId, vid);
                }
                break;

            case SKIP:
                // 门禁：完播率 < 10% 且播放时长 < 3 秒视为快速跳过
                if (playRatio < 0.1 && playDuration < 3 && primaryDomain != null) {
                    userProfile.recordDomainExposure(primaryDomain, false);
                    userProfileRepository.saveOrUpdate(userProfile);
                    log.debug("记录用户粗领域曝光未消费: userId={}, domain={}", userId, primaryDomain);
                }
                break;

            case DISLIKE:
                // 主动负反馈：自动沉淀为明确屏蔽并对粗领域惩罚
                if ("DISLIKE_AUTHOR".equalsIgnoreCase(reason) && authorId != null) {
                    userBlockRepository.save(UserBlock.create(userId, BlockType.AUTHOR, authorId, reason));
                } else {
                    userBlockRepository.save(UserBlock.create(userId, BlockType.VIDEO, vid, reason));
                }
                if (primaryDomain != null) {
                    userProfile.recordDomainExposure(primaryDomain, false);
                }
                userProfileRepository.saveOrUpdate(userProfile);
                log.info("记录用户主动负反馈并写入屏蔽门禁: userId={}, vid={}, reason={}", userId, vid, reason);
                break;

            case IMPRESSION:
            default:
                // 仅留存流水，不触发模型重算
                break;
        }
    }

    /**
     * 辅助获取视频特征浮点向量。
     */
    private List<Float> fetchVideoVector(String vid) {
        try {
            Optional<VideoVector> vectorOpt = videoVectorRepository.findByVid(vid);
            if (vectorOpt.isPresent() && vectorOpt.get().getVectorData() != null) {
                return objectMapper.readValue(vectorOpt.get().getVectorData(), new TypeReference<List<Float>>() {});
            }
        } catch (Exception e) {
            log.warn("读取视频特征向量解析异常: vid={}, error={}", vid, e.getMessage());
        }
        return Collections.emptyList();
    }

    private List<String> parseCommaList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String extractFirstTag(String raw) {
        List<String> list = parseCommaList(raw);
        return list.isEmpty() ? null : list.get(0);
    }
}
