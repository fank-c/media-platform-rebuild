package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.application.channel.RecommendRecallChannel;
import com.calles.platform.recommend.application.channel.impl.ExploreRecallChannel;
import com.calles.platform.recommend.application.channel.impl.FollowingRecallChannel;
import com.calles.platform.recommend.application.channel.impl.PersonalizedRecallChannel;
import com.calles.platform.recommend.application.channel.impl.TrendingRecallChannel;
import com.calles.platform.recommend.application.channel.model.RecallContext;
import com.calles.platform.recommend.application.channel.model.RecalledCandidate;
import com.calles.platform.recommend.application.dto.RecommendFeedResult;
import com.calles.platform.recommend.application.dto.RecommendItemResult;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.model.profile.UserProfile;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import com.calles.platform.recommend.domain.repository.UserProfileRepository;
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
 * 首页推荐流混合编排应用服务 (RecommendFeedApplicationService)。
 *
 * <p>核心流水线与业务职责：
 * <ul>
 *   <li><b>E&E 探索与利用多路召回</b>：按 50% 核心个性化、30% 探索发现、10% 近期高热度、10% 关注推荐并行召回；</li>
 *   <li><b>槽位交织混合 (Slot Blending)</b>：按预置模板交替填充卡片，杜绝同类内容聚簇，配额缺损时自适应向上吸收；</li>
 *   <li><b>统一硬过滤门禁</b>：一票否决非 ACTIVE 物料、作者本人作品、明确屏蔽黑名单（视频/作者/主题）与近期已看物料；</li>
 *   <li><b>多样性打散重排</b>：滑动窗口贪心打散，保证同一创作者卡片在输出流中间隔至少为 2；</li>
 *   <li><b>截断输出</b>：返回标准推荐结果 DTO 列表与分页标记。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendFeedApplicationService {

    /** 默认单屏卡片数量。 */
    private static final int DEFAULT_FEED_SIZE = 10;
    /** 单次请求最大允许拉取上限。 */
    private static final int MAX_FEED_SIZE = 50;
    /** 同作者卡片之间最小物理间隔数。 */
    private static final int AUTHOR_DE_DUP_GAP = 2;

    private final UserProfileRepository userProfileRepository;
    private final UserBlockRepository userBlockRepository;
    private final CandidateVideoRepository candidateVideoRepository;

    private final PersonalizedRecallChannel personalizedRecallChannel;
    private final ExploreRecallChannel exploreRecallChannel;
    private final TrendingRecallChannel trendingRecallChannel;
    private final FollowingRecallChannel followingRecallChannel;

    /**
     * 获取首页多路混合瀑布流推荐。
     *
     * @param userId 操作用户账号 ID (可为 null，代表游客未登录态)
     * @param size 请求期望获取的推荐数量
     * @return 编排打散后的最终推荐结果
     */
    public RecommendFeedResult getPersonalizedFeed(String userId, int size) {
        // 步骤 1：入参规整化
        int targetSize = (size <= 0) ? DEFAULT_FEED_SIZE : Math.min(size, MAX_FEED_SIZE);
        boolean isLogin = (userId != null && !userId.isBlank());
        String cleanUserId = isLogin ? userId.trim() : null;

        // 步骤 2：加载用户画像上下文与黑名单门禁
        UserProfile userProfile = null;
        List<UserBlock> userBlocks = Collections.emptyList();
        if (isLogin) {
            userProfile = userProfileRepository.findByUserId(cleanUserId).orElse(null);
            userBlocks = userBlockRepository.findByUserId(cleanUserId);
        }

        RecallContext context = RecallContext.builder()
                .userId(cleanUserId)
                .userProfile(userProfile)
                .userBlocks(userBlocks)
                .targetTotalSize(targetSize)
                .build();

        // 步骤 3：多路并行/分发召回 (按 50/30/10/10 计算各通道目标配额)
        int targetPersonalized = Math.max(1, (int) Math.round(targetSize * 0.5));
        int targetExplore = Math.max(1, (int) Math.round(targetSize * 0.3));
        int targetTrending = Math.max(1, (int) Math.round(targetSize * 0.1));
        int targetFollowing = Math.max(1, targetSize - targetPersonalized - targetExplore - targetTrending);

        List<RecalledCandidate> personalizedPool = new ArrayList<>(personalizedRecallChannel.recall(context, targetPersonalized * 2));
        List<RecalledCandidate> explorePool = new ArrayList<>(exploreRecallChannel.recall(context, targetExplore * 2));
        List<RecalledCandidate> trendingPool = new ArrayList<>(trendingRecallChannel.recall(context, targetTrending * 2));
        List<RecalledCandidate> followingPool = new ArrayList<>(followingRecallChannel.recall(context, targetFollowing * 2));

        // 步骤 4：槽位交织模板混合编排与自适应降级吸收
        List<RecalledCandidate> blendedItems = blendSlotsWithFallback(
                targetSize, personalizedPool, explorePool, trendingPool, followingPool
        );

        // 步骤 5：四道硬门禁统一收口过滤 (状态、自斥、拉黑、近期已看)
        List<RecalledCandidate> passedItems = applyHardFilters(blendedItems, cleanUserId, userProfile, userBlocks);

        // 步骤 6：多样性打散重排 (同作者物理间隔 >= 2)
        List<RecalledCandidate> reRankedItems = reRankWithDiversity(passedItems, targetSize);

        // 步骤 7：截断组装应用层结果 DTO
        List<RecommendItemResult> itemResults = reRankedItems.stream()
                .map(rc -> new RecommendItemResult(
                        rc.getCandidate().getVid(),
                        roundScore(rc.getScore()),
                        rc.getChannel(),
                        rc.getReason()
                ))
                .toList();

        boolean hasMore = passedItems.size() > reRankedItems.size();
        return new RecommendFeedResult(itemResults, hasMore);
    }

    /**
     * 槽位交织混合编排 (Slot Blending) 与降级吸收：
     * 按预置槽位模板依次挑选物料，若通道物料不足自动从可用通道或冷启动兜底池吸收。
     */
    private List<RecalledCandidate> blendSlotsWithFallback(
            int targetSize,
            List<RecalledCandidate> personalizedPool,
            List<RecalledCandidate> explorePool,
            List<RecalledCandidate> trendingPool,
            List<RecalledCandidate> followingPool) {

        List<RecalledCandidate> selected = new ArrayList<>();
        Set<String> selectedVids = new HashSet<>();

        // 槽位类型枚举
        String[] slotPattern = new String[]{
                "PERSONALIZED", "FOLLOWING", "PERSONALIZED", "EXPLORE",
                "TRENDING", "PERSONALIZED", "EXPLORE", "PERSONALIZED",
                "EXPLORE", "PERSONALIZED"
        };

        // 放大抓取配额池以供硬过滤淘汰后依然充足
        int requiredFetchSize = Math.max(targetSize * 2, 20);

        for (int i = 0; i < requiredFetchSize; i++) {
            String preferredType = slotPattern[i % slotPattern.length];
            RecalledCandidate candidate = pickCandidateByType(
                    preferredType, selectedVids, personalizedPool, explorePool, trendingPool, followingPool
            );

            if (candidate != null) {
                selected.add(candidate);
                selectedVids.add(candidate.getCandidate().getVid());
            }
        }

        // 若仍不足期望拉取量，从最新活跃候选池保底补齐
        if (selected.size() < targetSize) {
            List<CandidateVideo> fallbacks = candidateVideoRepository.findRecentActive(targetSize * 2);
            LocalDateTime now = LocalDateTime.now();
            for (CandidateVideo cv : fallbacks) {
                if (selected.size() >= targetSize) {
                    break;
                }
                if (!selectedVids.contains(cv.getVid()) && cv.isRecommendable()) {
                    long hoursAgo = (cv.getPublishedAt() != null)
                            ? Math.max(0, Duration.between(cv.getPublishedAt(), now).toHours())
                            : 0;
                    double score = 1.0 / (1.0 + hoursAgo / 24.0);
                    selected.add(new RecalledCandidate(cv, "COLD_START", score, "新鲜发布"));
                    selectedVids.add(cv.getVid());
                }
            }
        }

        return selected;
    }

    /**
     * 优先根据槽位期望类型挑选候选物料；若该通道为空则自适应回退至其他可用通道。
     */
    private RecalledCandidate pickCandidateByType(
            String preferredType,
            Set<String> selectedVids,
            List<RecalledCandidate> personalizedPool,
            List<RecalledCandidate> explorePool,
            List<RecalledCandidate> trendingPool,
            List<RecalledCandidate> followingPool) {

        List<RecalledCandidate> primaryList = switch (preferredType) {
            case "FOLLOWING" -> followingPool;
            case "EXPLORE" -> explorePool;
            case "TRENDING" -> trendingPool;
            default -> personalizedPool;
        };

        // 优先从目标池挑选
        RecalledCandidate picked = extractFirstUnused(primaryList, selectedVids);
        if (picked != null) {
            return picked;
        }

        // 目标池已空，降级回补吸收：核心池 -> 探索池 -> 热度池 -> 关注池
        picked = extractFirstUnused(personalizedPool, selectedVids);
        if (picked != null) return picked;

        picked = extractFirstUnused(explorePool, selectedVids);
        if (picked != null) return picked;

        picked = extractFirstUnused(trendingPool, selectedVids);
        if (picked != null) return picked;

        return extractFirstUnused(followingPool, selectedVids);
    }

    /**
     * 从指定列表中提取首个尚未被选中的物料。
     */
    private RecalledCandidate extractFirstUnused(List<RecalledCandidate> pool, Set<String> selectedVids) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        Iterator<RecalledCandidate> it = pool.iterator();
        while (it.hasNext()) {
            RecalledCandidate item = it.next();
            if (!selectedVids.contains(item.getCandidate().getVid())) {
                it.remove();
                return item;
            }
        }
        return null;
    }

    /**
     * 执行四道硬过滤门禁。
     */
    private List<RecalledCandidate> applyHardFilters(
            Collection<RecalledCandidate> items,
            String userId,
            UserProfile userProfile,
            List<UserBlock> userBlocks) {

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

        List<RecalledCandidate> passed = new ArrayList<>();
        Set<String> seenVids = new HashSet<>();

        for (RecalledCandidate rc : items) {
            CandidateVideo cv = rc.getCandidate();

            // 防重保护
            if (seenVids.contains(cv.getVid())) {
                continue;
            }

            // 门禁 1：物料状态必须为 ACTIVE
            if (!cv.isRecommendable()) {
                continue;
            }

            // 门禁 2：不可推荐作者本人发布的作品
            if (userId != null && userId.equals(cv.getAuthorId())) {
                continue;
            }

            // 门禁 3：明确屏蔽门禁
            if (blockedVids.contains(cv.getVid())) {
                continue;
            }
            if (blockedAuthors.contains(cv.getAuthorId())) {
                continue;
            }
            if (hasIntersection(parseCommaTags(cv.getTopicTagIds()), blockedTopics)) {
                continue;
            }

            // 门禁 4：近期观看历史去重
            if (userProfile != null && userProfile.hasWatchedRecently(cv.getVid())) {
                continue;
            }

            passed.add(rc);
            seenVids.add(cv.getVid());
        }

        return passed;
    }

    /**
     * 多样性滑动窗口打散重排：
     * 保证同作者物料在最终输出流中至少间隔 AUTHOR_DE_DUP_GAP 个卡片。
     */
    private List<RecalledCandidate> reRankWithDiversity(List<RecalledCandidate> sortedCandidates, int targetSize) {
        List<RecalledCandidate> result = new ArrayList<>();
        List<RecalledCandidate> deferred = new ArrayList<>();

        // 第一轮：严格遵守作者打散贪心选取
        for (RecalledCandidate sc : sortedCandidates) {
            if (result.size() >= targetSize) {
                break;
            }
            String authorId = sc.getCandidate().getAuthorId();
            if (isAuthorTooClose(result, authorId, AUTHOR_DE_DUP_GAP)) {
                deferred.add(sc);
            } else {
                result.add(sc);
            }
        }

        // 第二轮：若未填满配额，从暂存区中尝试以满足间隔的方式填入
        if (result.size() < targetSize && !deferred.isEmpty()) {
            Iterator<RecalledCandidate> it = deferred.iterator();
            while (it.hasNext() && result.size() < targetSize) {
                RecalledCandidate sc = it.next();
                String authorId = sc.getCandidate().getAuthorId();
                if (!isAuthorTooClose(result, authorId, AUTHOR_DE_DUP_GAP)) {
                    result.add(sc);
                    it.remove();
                }
            }

            // 第三轮：保底输出（极端全同作者时宽松放行）
            if (result.size() < targetSize && !deferred.isEmpty()) {
                for (RecalledCandidate sc : deferred) {
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

    private boolean isAuthorTooClose(List<RecalledCandidate> selected, String authorId, int minGap) {
        if (selected == null || selected.isEmpty() || authorId == null) {
            return false;
        }
        int startIndex = Math.max(0, selected.size() - minGap);
        for (int i = selected.size() - 1; i >= startIndex; i--) {
            if (authorId.equals(selected.get(i).getCandidate().getAuthorId())) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseCommaTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

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

    private double roundScore(double score) {
        return BigDecimal.valueOf(score)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
