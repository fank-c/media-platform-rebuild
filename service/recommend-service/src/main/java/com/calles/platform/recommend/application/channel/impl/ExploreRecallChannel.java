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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 探索发现推荐通道 (ExploreRecallChannel)。
 *
 * <p>核心职责：
 * <ul>
 *   <li><b>目标配比</b>：占据单次推送的 30% 配额（包含 20% 近似探索 + 10% 跨领域随机探索）；</li>
 *   <li><b>核心定位</b>：破除信息茧房（Filter Bubble），探测用户潜在兴趣与全新内容品类；</li>
 *   <li><b>算法依赖</b>：
 *     1. <b>近似探索 (20%)</b>：基于向量空间次优区间（Rank 6~25）或次级兴趣标签延伸；
 *     2. <b>跨领域探索 (10%)</b>：采样用户画像中从未曝光过的粗领域优质物料。
 *   </li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExploreRecallChannel extends AbstractRecallChannel {

    /** 通道唯一业务标识。 */
    public static final String CHANNEL_NAME = "EXPLORE";

    /** 近似兴趣探索子渠道标识 (占据 20% 配额，聚焦次优向量区间)。 */
    public static final String SUB_CHANNEL_SIMILAR = "EXPLORE_SIMILAR";

    /** 跨领域随机破圈子渠道标识 (占据 10% 配额，聚焦未涉足全新领域)。 */
    public static final String SUB_CHANNEL_RANDOM = "EXPLORE_RANDOM";

    /** 该通道在多路推荐混合流中的综合基准配比 (30%)。 */
    private static final int TARGET_PERCENTAGE = 30;

    /** 向量数据库客户端组件。 */
    private final QdrantClient qdrantClient;

    /** Qdrant 集合与连接配置。 */
    private final QdrantProperties qdrantProperties;

    /** 候选视频物料持久化仓储。 */
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
    public List<RecalledCandidate> recall(RecallContext context, int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }

        // 计算内部拆分：近似探索 2/3 (约 20%)，跨领域随机 1/3 (约 10%)
        int randomCount = Math.max(1, count / 3);
        int similarCount = Math.max(1, count - randomCount);

        List<RecalledCandidate> result = new ArrayList<>();

        // 步骤 1：近似探索召回 (Similar Exploration)
        List<RecalledCandidate> similarItems = recallSimilarCandidates(context, similarCount * 2);
        for (RecalledCandidate rc : similarItems) {
            if (result.size() < similarCount) {
                result.add(rc);
            }
        }

        // 步骤 2：跨领域探索召回 (Cross-domain Random Exploration)
        Set<String> alreadyPickedVids = new HashSet<>();
        result.forEach(r -> alreadyPickedVids.add(r.getCandidate().getVid()));

        List<RecalledCandidate> randomItems = recallCrossDomainCandidates(context, randomCount * 2, alreadyPickedVids);
        for (RecalledCandidate rc : randomItems) {
            if (result.size() < count && !alreadyPickedVids.contains(rc.getCandidate().getVid())) {
                result.add(rc);
                alreadyPickedVids.add(rc.getCandidate().getVid());
            }
        }

        // 步骤 3：若配额仍有空缺，从候选池最新物料中补齐探索配额
        if (result.size() < count) {
            List<CandidateVideo> fallbackVideos = candidateVideoRepository.findRecentActive(count * 3);
            LocalDateTime now = LocalDateTime.now();
            for (CandidateVideo cv : fallbackVideos) {
                if (result.size() >= count) {
                    break;
                }
                if (!alreadyPickedVids.contains(cv.getVid())) {
                    long hoursAgo = (cv.getPublishedAt() != null)
                            ? Math.max(0, Duration.between(cv.getPublishedAt(), now).toHours())
                            : 0;
                    double score = 1.0 / (1.0 + hoursAgo / 24.0);
                    result.add(new RecalledCandidate(cv, SUB_CHANNEL_RANDOM, score, "探索新鲜发现"));
                    alreadyPickedVids.add(cv.getVid());
                }
            }
        }

        return result;
    }

    /** 近似探索向量检索深度上限 (30~40 之间的逃离窗口)。 */
    private static final int SIMILAR_VECTOR_SEARCH_LIMIT = 35;

    /**
     * 召回近似探索物料：通过受限窗口向量检索与倒排，并在用户活跃粗领域内发散探索。
     *
     * <p>算法设计说明：
     * <ul>
     *   <li><b>逃离窗口与倒排</b>：限制检索深度在 30~40 之间（{@value #SIMILAR_VECTOR_SEARCH_LIMIT}），
     *       倒排后按余弦相似度由低到高遍历，锁定外围弱关联物料，逃离高密度同质向量簇；</li>
     *   <li><b>粗标签校验保底</b>：仅校验物料的主领域是否属于用户已有活跃大领域（Domain），
     *       确保与用户兴趣保持一定大方向关联，同时不对细标签做人为约束，实现同大类下细分支的自然随机探索；</li>
     *   <li><b>防重叠机制</b>：与核心个性化通道形成天然空间与语义错位，极大降低后续槽位交织冲突率。</li>
     * </ul>
     * </p>
     *
     * @param context 召回上下文
     * @param limit 期望获取的最大候选数量
     * @return 过滤后的近似探索物料列表
     */
    private List<RecalledCandidate> recallSimilarCandidates(RecallContext context, int limit) {
        if (!context.isLogin() || context.getUserProfile() == null) {
            return Collections.emptyList();
        }
        UserProfile profile = context.getUserProfile();
        if (profile.getUserVector() == null || profile.getUserVector().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 步骤 1：发起深度受限的 ANN 检索 (30~40 逃离窗口)
            List<ScoredPoint> points = qdrantClient.searchPoints(
                    qdrantProperties.getCollectionName(),
                    profile.getUserVector().getVector(),
                    SIMILAR_VECTOR_SEARCH_LIMIT
            );

            if (points == null || points.isEmpty()) {
                return Collections.emptyList();
            }

            // 步骤 2：对检索点集合执行倒排 (余弦相似度由低到高，锁定弱关联边缘带)
            List<ScoredPoint> reversedPoints = new ArrayList<>(points);
            Collections.reverse(reversedPoints);

            List<String> vids = new ArrayList<>();
            Map<String, Double> scoreMap = new HashMap<>();

            for (ScoredPoint p : reversedPoints) {
                String vid = extractVidFromPoint(p);
                if (vid != null && !vid.isBlank()) {
                    vids.add(vid);
                    scoreMap.put(vid, p.score() != null ? Math.max(0.01, p.score()) : 0.5);
                }
            }

            if (vids.isEmpty()) {
                return Collections.emptyList();
            }

            // 步骤 3：提取用户活跃的偏好粗领域集合 (未被严重抑制的大类)
            Set<String> activeDomains = Collections.emptySet();
            if (profile.getDomainStates() != null && !profile.getDomainStates().isEmpty()) {
                activeDomains = profile.getDomainStates().keySet();
            }

            // 步骤 4：批量加载候选实体，按倒排顺序基于粗标签过滤采纳
            List<CandidateVideo> candidates = candidateVideoRepository.findByVids(vids);
            Map<String, CandidateVideo> candidateMap = new HashMap<>();
            for (CandidateVideo cv : candidates) {
                if (cv != null && cv.getVid() != null) {
                    candidateMap.put(cv.getVid(), cv);
                }
            }

            List<RecalledCandidate> list = new ArrayList<>();
            for (String vid : vids) {
                if (list.size() >= limit) {
                    break;
                }
                CandidateVideo cv = candidateMap.get(vid);
                if (cv == null || !cv.isRecommendable()) {
                    continue;
                }

                // 粗标签 (Domain) 校验：若用户已建立粗领域画像，物料主领域必须命中，保证近似性
                String primaryDomain = extractPrimaryTag(cv.getDomainTagIds());
                if (!activeDomains.isEmpty() && (primaryDomain == null || !activeDomains.contains(primaryDomain))) {
                    continue;
                }

                Double rawScore = scoreMap.get(cv.getVid());
                double score = rawScore != null ? rawScore : 0.6;
                list.add(new RecalledCandidate(cv, SUB_CHANNEL_SIMILAR, score, "相似领域探索"));
            }

            return list;
        } catch (Exception ex) {
            log.warn("近似探索向量召回发生异常，降级为空列表: error={}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 召回跨领域随机探索物料：优先挑选用户画像中尚未产生曝光或互动的粗领域。
     *
     * <p>打破信息茧房（Filter Bubble），探测全新兴趣点，若全域物料不足则混入新鲜活跃物料兜底。</p>
     *
     * @param context 召回上下文
     * @param limit 期望获取的最大候选数量
     * @param excludeVids 已被前置步骤选中的视频短码集合（防止通道内同物料重复）
     * @return 跨领域破圈候选物料列表
     */
    private List<RecalledCandidate> recallCrossDomainCandidates(RecallContext context, int limit, Set<String> excludeVids) {
        // 步骤 1：提取用户已有画像中已涉足曝光过的领域标签集合
        Set<String> exposedDomains = Collections.emptySet();
        if (context.getUserProfile() != null && context.getUserProfile().getDomainStates() != null) {
            exposedDomains = context.getUserProfile().getDomainStates().keySet();
        }

        List<CandidateVideo> recentVideos = candidateVideoRepository.findRecentActive(limit * 3);
        List<RecalledCandidate> result = new ArrayList<>();

        // 步骤 2：优先挑选用户从未涉足过的全新粗领域物料
        for (CandidateVideo cv : recentVideos) {
            if (result.size() >= limit) {
                break;
            }
            if (excludeVids.contains(cv.getVid()) || !cv.isRecommendable()) {
                continue;
            }
            String primaryDomain = extractPrimaryTag(cv.getDomainTagIds());
            if (primaryDomain != null && !exposedDomains.contains(primaryDomain)) {
                result.add(new RecalledCandidate(cv, SUB_CHANNEL_RANDOM, 0.7, "探索新领域"));
            }
        }

        // 步骤 3：若全新领域物料不足，放宽条件混入最新发布的新鲜物料补充配额
        if (result.size() < limit) {
            for (CandidateVideo cv : recentVideos) {
                if (result.size() >= limit) {
                    break;
                }
                if (excludeVids.contains(cv.getVid()) || !cv.isRecommendable()) {
                    continue;
                }
                boolean alreadyIn = result.stream().anyMatch(r -> r.getCandidate().getVid().equals(cv.getVid()));
                if (!alreadyIn) {
                    result.add(new RecalledCandidate(cv, SUB_CHANNEL_RANDOM, 0.5, "为你推荐更多发现"));
                }
            }
        }

        return result;
    }
}
