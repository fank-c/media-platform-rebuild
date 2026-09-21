package com.calles.platform.recommend.application.channel.impl;

import com.calles.platform.recommend.application.channel.RecommendRecallChannel;
import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;

import com.calles.platform.recommend.config.QdrantProperties;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.infrastructure.qdrant.QdrantClient;
import com.calles.platform.recommend.infrastructure.qdrant.dto.QdrantDTOs.ScoredPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 核心个性化利用推荐通道 (PersonalizedRecallChannel)。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>目标配比</b>：占据单次推送的 50% 核心配额；</li>
 *   <li><b>核心定位</b>：满足用户已知偏好，保障基础消费时长与完播基本盘；</li>
 *   <li><b>算法依赖</b>：基于 Qdrant ANN 向量余弦检索 Top 相似段，叠加细主题偏好分与粗领域抑制微调。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalizedRecallChannel extends AbstractRecallChannel {

    /** 通道唯一业务标识。 */
    public static final String CHANNEL_NAME = "PERSONALIZED";

    /** 该通道在多路推荐混合流中的核心基准配比 (50%)。 */
    private static final int TARGET_PERCENTAGE = 50;

    /** 向量数据库客户端组件。 */
    private final QdrantClient qdrantClient;

    /** Qdrant 连接与集合配置属性。 */
    private final QdrantProperties qdrantProperties;

    /** 推荐候选池数据仓储。 */
    private final CandidateVideoRepository candidateVideoRepository;

    @Override
    public String getChannelName() {
        return CHANNEL_NAME;
    }

    @Override
    public int getTargetRatioPercentage() {
        return TARGET_PERCENTAGE;
    }

    @Override
    public boolean supports(RecallContext context) {
        return context != null
                && context.isLogin()
                && context.getUserProfile() != null
                && context.getUserProfile().getUserVector() != null
                && !context.getUserProfile().getUserVector().isEmpty();
    }

    @Override
    public List<RecalledCandidate> recall(RecallContext context, int count) {
        // 步骤 1：前置检查登录态与向量画像
        if (!context.isLogin() || context.getUserProfile() == null) {
            return Collections.emptyList();
        }
        UserProfile profile = context.getUserProfile();
        if (profile.getUserVector() == null || profile.getUserVector().isEmpty()) {
            return Collections.emptyList();
        }

        // 步骤 2：发起 Qdrant 向量 ANN 检索 (放大召回量)
        int fetchLimit = Math.max(count * 4, 30);
        List<ScoredPoint> points;
        try {
            points = qdrantClient.searchPoints(
                    qdrantProperties.getCollectionName(),
                    profile.getUserVector().getVector(),
                    fetchLimit
            );
        } catch (Exception ex) {
            log.warn("核心个性化向量召回异常，降级为空: error={}", ex.getMessage());
            return Collections.emptyList();
        }

        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }

        // 步骤 3：提取短码与基准余弦打分
        Map<String, Double> vectorScoreMap = new LinkedHashMap<>();
        for (ScoredPoint point : points) {
            String vid = extractVidFromPoint(point);
            if (vid != null && !vid.isBlank()) {
                double rawScore = point.score() != null ? point.score() : 0.5;
                double baseScore = Math.max(0.01, Math.min(1.0, rawScore));
                vectorScoreMap.put(vid, baseScore);
            }
        }

        if (vectorScoreMap.isEmpty()) {
            return Collections.emptyList();
        }

        // 步骤 4：批量对齐候选物料实体
        List<CandidateVideo> candidates = candidateVideoRepository.findByVids(new ArrayList<>(vectorScoreMap.keySet()));
        List<RecalledCandidate> result = new ArrayList<>();

        for (CandidateVideo cv : candidates) {
            if (!cv.isRecommendable()) {
                continue;
            }
            Double baseScore = vectorScoreMap.get(cv.getVid());
            if (baseScore == null) {
                continue;
            }

            // 粗领域弱负向打折
            String primaryDomain = extractPrimaryTag(cv.getDomainTagIds());
            double domainSuppression = (primaryDomain != null)
                    ? profile.getDomainSuppressionFactor(primaryDomain)
                    : 1.0;

            // 细主题偏好微调加分
            double topicBonus = 0.0;
            String bestTopicReason = null;
            double maxTopicScore = 0.0;
            if (cv.getTopicTagIds() != null && !cv.getTopicTagIds().isBlank()) {
                for (String t : cv.getTopicTagIds().split(",")) {
                    String tag = t.trim();
                    if (!tag.isEmpty()) {
                        double ts = profile.getTopicScore(tag);
                        if (ts > 0) {
                            topicBonus += ts * 0.05;
                            if (ts > maxTopicScore) {
                                maxTopicScore = ts;
                                bestTopicReason = tag;
                            }
                        }
                    }
                }
            }
            topicBonus = Math.min(topicBonus, 0.5);

            double finalScore = (baseScore * domainSuppression) + topicBonus;
            String reason = (bestTopicReason != null) ? "偏好标签推荐" : "为你量身精选";

            result.add(new RecalledCandidate(cv, CHANNEL_NAME, finalScore, reason));
        }

        // 步骤 5：通道内降序排序并截断
        result.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return result.size() > count ? result.subList(0, count) : result;
    }
}
