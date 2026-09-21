package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.application.dto.RecommendFeedResult;
import com.calles.platform.recommend.application.dto.RecommendItemResult;
import com.calles.platform.recommend.config.QdrantProperties;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import com.calles.platform.recommend.domain.repository.UserProfileRepository;
import com.calles.platform.recommend.infrastructure.qdrant.QdrantClient;
import com.calles.platform.recommend.infrastructure.qdrant.dto.QdrantDTOs.ScoredPoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 首页个性化推荐流编排应用服务 (RecommendFeedApplicationService)。
 *
 * <p>职责与流水线架构：
 * <ul>
 *   <li><b>多路召回 (Recall)</b>：针对有向量历史的用户发起 Qdrant 向量 ANN 检索；冷启动/游客/向量异常时平滑回退最新候选池；</li>
 *   <li><b>硬门禁过滤 (Hard Filter)</b>：严格剔除下线/封禁物料、本人作品、明确屏蔽黑名单（视频/作者/主题）以及近期已看物料（防出屏重复）；</li>
 *   <li><b>粗细标签微调打分 (Scoring)</b>：以向量余弦相似度或发布新鲜度为 BaseScore，乘粗领域疲劳抑制系数，加细主题偏好分；</li>
 *   <li><b>多样性打散重排 (Diversity Re-rank)</b>：滑动窗口贪心重排，保障同作者卡片物理间隔至少为 2；</li>
 *   <li><b>截断交付 (Truncation)</b>：输出业务公开短码 {@code vid}、综合打分与推荐理由。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendFeedApplicationService {

    /** 默认单屏物料拉取数量。 */
    private static final int DEFAULT_FEED_SIZE = 10;
    /** 单次请求最大允许拉取上限。 */
    private static final int MAX_FEED_SIZE = 50;
    /** 同作者卡片之间最小间隔物料数。 */
    private static final int AUTHOR_DE_DUP_GAP = 2;

    private final UserProfileRepository userProfileRepository;
    private final UserBlockRepository userBlockRepository;
    private final CandidateVideoRepository candidateVideoRepository;
    private final QdrantClient qdrantClient;
    private final QdrantProperties qdrantProperties;

    /**
     * 内部召回物料临时封装载荷。
     */
    private record RecalledItem(
            CandidateVideo candidate,
            String channel,
            double baseScore
    ) {}

    /**
     * 内部算分重排临时封装载荷。
     */
    private record ScoredCandidate(
            CandidateVideo candidate,
            String channel,
            double finalScore,
            String reason
    ) {}

    /**
     * 获取首页个性化推荐瀑布流。
     *
     * @param userId 操作用户账号 ID (可为 null，代表游客未登录态)
     * @param size 请求推荐数量
     * @return 编排计算后的推荐结果集合
     */
    public RecommendFeedResult getPersonalizedFeed(String userId, int size) {
        // 步骤 1：入参规整化与身份校验
        int targetSize = (size <= 0) ? DEFAULT_FEED_SIZE : Math.min(size, MAX_FEED_SIZE);
        boolean isLogin = (userId != null && !userId.isBlank());
        String cleanUserId = isLogin ? userId.trim() : null;

        // 步骤 2：加载上下文画像与黑名单门禁
        UserProfile userProfile = null;
        List<UserBlock> userBlocks = Collections.emptyList();
        if (isLogin) {
            userProfile = userProfileRepository.findByUserId(cleanUserId).orElse(null);
            userBlocks = userBlockRepository.findByUserId(cleanUserId);
        }

        // 步骤 3：多路召回 (向量 ANN 召回 + 候选池冷启动/保底)
        Map<String, RecalledItem> recalledMap = executeMultiChannelRecall(userProfile, targetSize);

        // 步骤 4：硬过滤门禁 (状态、作者本人、明确屏蔽、近期已看)
        List<RecalledItem> filteredItems = applyHardFilters(recalledMap.values(), cleanUserId, userProfile, userBlocks);

        // 步骤 5：粗细加权综合打分
        List<ScoredCandidate> scoredCandidates = calculateScores(filteredItems, userProfile);

        // 步骤 6：多样性打散重排 (同作者物理间隔 >= 2)
        List<ScoredCandidate> reRankedCandidates = reRankWithDiversity(scoredCandidates, targetSize);

        // 步骤 7：截断组装应用层结果 DTO
        List<RecommendItemResult> itemResults = reRankedCandidates.stream()
                .map(sc -> new RecommendItemResult(
                        sc.candidate().getVid(),
                        roundScore(sc.finalScore()),
                        sc.channel(),
                        sc.reason()
                ))
                .toList();

        boolean hasMore = scoredCandidates.size() > reRankedCandidates.size();
        return new RecommendFeedResult(itemResults, hasMore);
    }

    /**
     * 执行多路召回：向量召回通道与冷启动保底池。
     */
    private Map<String, RecalledItem> executeMultiChannelRecall(UserProfile userProfile, int targetSize) {
        Map<String, RecalledItem> resultMap = new LinkedHashMap<>();
        int recallLimit = Math.max(targetSize * 4, 40);

        // 通道 A：向量 ANN 检索召回 (用户已建有有效特征向量时)
        if (userProfile != null && userProfile.getUserVector() != null && !userProfile.getUserVector().isEmpty()) {
            try {
                List<ScoredPoint> points = qdrantClient.searchPoints(
                        qdrantProperties.getCollectionName(),
                        userProfile.getUserVector().getVector(),
                        recallLimit
                );

                if (points != null && !points.isEmpty()) {
                    Map<String, Double> vectorScoreMap = new LinkedHashMap<>();
                    for (ScoredPoint point : points) {
                        String vid = extractVidFromPoint(point);
                        if (vid != null && !vid.isBlank()) {
                            double rawScore = point.score() != null ? point.score() : 0.5;
                            // 余弦相似度归一化保底
                            double baseScore = Math.max(0.01, Math.min(1.0, rawScore));
                            vectorScoreMap.put(vid, baseScore);
                        }
                    }

                    if (!vectorScoreMap.isEmpty()) {
                        List<CandidateVideo> candidates = candidateVideoRepository.findByVids(new ArrayList<>(vectorScoreMap.keySet()));
                        for (CandidateVideo cv : candidates) {
                            Double score = vectorScoreMap.get(cv.getVid());
                            if (score != null) {
                                resultMap.put(cv.getVid(), new RecalledItem(cv, "VECTOR", score));
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("向量召回发生异常，降级依赖兜底池: error={}", ex.getMessage());
            }
        }

        // 通道 B：候选池最新物料召回 (冷启动/补齐不足配额)
        if (resultMap.size() < targetSize * 2) {
            int coldStartLimit = Math.max(targetSize * 3, 30);
            List<CandidateVideo> activeVideos = candidateVideoRepository.findRecentActive(coldStartLimit);
            LocalDateTime now = LocalDateTime.now();

            for (CandidateVideo cv : activeVideos) {
                if (!resultMap.containsKey(cv.getVid())) {
                    // 冷启动物料根据发布时间计算新鲜度基础分 (24小时内平滑过渡)
                    long hoursAgo = (cv.getPublishedAt() != null)
                            ? Math.max(0, Duration.between(cv.getPublishedAt(), now).toHours())
                            : 0;
                    double freshnessScore = 1.0 / (1.0 + hoursAgo / 24.0);
                    resultMap.put(cv.getVid(), new RecalledItem(cv, "COLD_START", freshnessScore));
                }
            }
        }

        return resultMap;
    }

    /**
     * 从 Qdrant 检索点提取视频公开业务短码 vid。
     */
    private String extractVidFromPoint(ScoredPoint point) {
        if (point == null) {
            return null;
        }
        if (point.payload() != null && point.payload().get("vid") != null) {
            return point.payload().get("vid").toString();
        }
        return point.id();
    }

    /**
     * 执行四道硬过滤门禁。
     */
    private List<RecalledItem> applyHardFilters(Collection<RecalledItem> items,
                                                String userId,
                                                UserProfile userProfile,
                                                List<UserBlock> userBlocks) {
        // 预处理屏蔽黑名单
        Set<String> blockedVids = new HashSet<>();
        Set<String> blockedAuthors = new HashSet<>();
        Set<String> blockedTopics = new HashSet<>();

        if (userBlocks != null) {
            for (UserBlock block : userBlocks) {
                if (block.getBlockType() == BlockType.VIDEO) {
                    blockedVids.add(block.getTargetId());
                } else if (block.getBlockType() == BlockType.AUTHOR) {
                    blockedAuthors.add(block.getTargetId());
                } else if (block.getBlockType() == BlockType.TOPIC) {
                    blockedTopics.add(block.getTargetId());
                }
            }
        }

        List<RecalledItem> passedItems = new ArrayList<>();
        for (RecalledItem item : items) {
            CandidateVideo cv = item.candidate();

            // 门禁 1：物料状态必须为 ACTIVE
            if (!cv.isRecommendable()) {
                continue;
            }

            // 门禁 2：不可推荐作者本人发布的作品
            if (userId != null && userId.equals(cv.getAuthorId())) {
                continue;
            }

            // 门禁 3：明确屏蔽门禁 (视频短码、创作者账号、关联细主题)
            if (blockedVids.contains(cv.getVid())) {
                continue;
            }
            if (blockedAuthors.contains(cv.getAuthorId())) {
                continue;
            }
            if (hasIntersection(parseCommaTags(cv.getTopicTagIds()), blockedTopics)) {
                continue;
            }

            // 门禁 4：近期观看历史去重 (短期出屏疲劳过滤)
            if (userProfile != null && userProfile.hasWatchedRecently(cv.getVid())) {
                continue;
            }

            passedItems.add(item);
        }

        return passedItems;
    }

    /**
     * 粗细标签微调综合算分并排序。
     * 公式: FinalScore = BaseScore * DomainSuppression + TopicBonus
     */
    private List<ScoredCandidate> calculateScores(List<RecalledItem> items, UserProfile userProfile) {
        List<ScoredCandidate> scoredList = new ArrayList<>();

        for (RecalledItem item : items) {
            CandidateVideo cv = item.candidate();

            // 粗领域弱负向惩罚抑制
            String primaryDomain = extractPrimaryTag(cv.getDomainTagIds());
            double domainSuppression = (userProfile != null && primaryDomain != null)
                    ? userProfile.getDomainSuppressionFactor(primaryDomain)
                    : 1.0;

            // 细主题个性化微调加分
            double topicBonus = 0.0;
            String bestTopicReason = null;
            double maxTopicScore = 0.0;

            if (userProfile != null && cv.getTopicTagIds() != null && !cv.getTopicTagIds().isBlank()) {
                List<String> topics = parseCommaTags(cv.getTopicTagIds());
                for (String tagId : topics) {
                    double ts = userProfile.getTopicScore(tagId);
                    if (ts > 0) {
                        topicBonus += ts * 0.05;
                        if (ts > maxTopicScore) {
                            maxTopicScore = ts;
                            bestTopicReason = tagId;
                        }
                    }
                }
            }
            // 细主题加权封顶 0.5，防标签极化覆盖特征向量
            topicBonus = Math.min(topicBonus, 0.5);

            // 综合打分
            double finalScore = (item.baseScore() * domainSuppression) + topicBonus;

            // 生成用户侧可解释推荐理由
            String reason;
            if (bestTopicReason != null) {
                reason = "偏好标签推荐";
            } else if ("VECTOR".equals(item.channel())) {
                reason = "为你量身精选";
            } else {
                reason = "新鲜发布";
            }

            scoredList.add(new ScoredCandidate(cv, item.channel(), finalScore, reason));
        }

        // 依据最终打分降序排列
        scoredList.sort((a, b) -> Double.compare(b.finalScore(), a.finalScore()));
        return scoredList;
    }

    /**
     * 多样性滑动窗口打散重排：
     * 同作者物料在最终输出流中至少间隔 AUTHOR_DE_DUP_GAP 个卡片。
     */
    private List<ScoredCandidate> reRankWithDiversity(List<ScoredCandidate> sortedCandidates, int targetSize) {
        List<ScoredCandidate> result = new ArrayList<>();
        List<ScoredCandidate> deferred = new ArrayList<>();

        // 第一轮：严格遵守作者打散贪心选取
        for (ScoredCandidate sc : sortedCandidates) {
            if (result.size() >= targetSize) {
                break;
            }
            String authorId = sc.candidate().getAuthorId();
            if (isAuthorTooClose(result, authorId, AUTHOR_DE_DUP_GAP)) {
                deferred.add(sc);
            } else {
                result.add(sc);
            }
        }

        // 第二轮：若未填满配额，从暂存区中尝试以满足间隔的方式填入
        if (result.size() < targetSize && !deferred.isEmpty()) {
            Iterator<ScoredCandidate> it = deferred.iterator();
            while (it.hasNext() && result.size() < targetSize) {
                ScoredCandidate sc = it.next();
                String authorId = sc.candidate().getAuthorId();
                if (!isAuthorTooClose(result, authorId, AUTHOR_DE_DUP_GAP)) {
                    result.add(sc);
                    it.remove();
                }
            }

            // 第三轮：保底输出（候选库不足时的宽松放行）
            if (result.size() < targetSize && !deferred.isEmpty()) {
                for (ScoredCandidate sc : deferred) {
                    if (result.size() >= targetSize) {
                        break;
                    }
                    if (!result.contains(sc)) {
                        result.add(sc);
                    }
                }
            }
        }

        return result;
    }

    /**
     * 判定指定作者是否在当前结果列表尾部窗口内连续出现。
     */
    private boolean isAuthorTooClose(List<ScoredCandidate> selected, String authorId, int minGap) {
        if (selected == null || selected.isEmpty() || authorId == null) {
            return false;
        }
        int startIndex = Math.max(0, selected.size() - minGap);
        for (int i = selected.size() - 1; i >= startIndex; i--) {
            if (authorId.equals(selected.get(i).candidate().getAuthorId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析逗号分隔的标签字符串。
     */
    private List<String> parseCommaTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 提取主领域标签 (首个领域标签)。
     */
    private String extractPrimaryTag(String tags) {
        List<String> list = parseCommaTags(tags);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 检查两个集合是否存在交集。
     */
    private boolean hasIntersection(List<String> list, Set<String> set) {
        if (list == null || list.isEmpty() || set == null || set.isEmpty()) {
            return false;
        }
        for (String item : list) {
            if (set.contains(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分数精度修约 (保留4位小数)。
     */
    private double roundScore(double score) {
        return BigDecimal.valueOf(score)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
