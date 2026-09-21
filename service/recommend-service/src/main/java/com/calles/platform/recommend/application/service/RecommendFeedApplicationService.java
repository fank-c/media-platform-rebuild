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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 首页推荐流混合编排应用服务 (RecommendFeedApplicationService)。
 *
 * <p>核心流水线与业务职责：
 * <ul>
 *   <li><b>开闭原则与多路并行召回</b>：统一调度所有注册的 {@link RecommendRecallChannel} 召回通道，利用 Java 21 虚拟线程并发拉取；</li>
 *   <li><b>前置硬过滤门禁</b>：在混合前统一对各路候选池执行四道硬过滤（非 ACTIVE 状态、作者本人、明确拉黑、近期已看），杜绝后续槽位空洞；</li>
 *   <li><b>槽位交织混合 (Slot Blending)</b>：按预置模板交替填充卡片，队列 O(1) 提取，通道物料不足时按权重顺位自适应吸收；</li>
 *   <li><b>冷启动与兜底</b>：全通道物料耗尽时自动从最新活跃候选池保底补齐，并同步遵守硬过滤门禁；</li>
 *   <li><b>多样性打散重排</b>：滑动窗口贪心打散，保证同一创作者卡片在输出流中间隔至少为 2；</li>
 *   <li><b>截断输出</b>：返回标准推荐结果 DTO 列表与分页标记。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class RecommendFeedApplicationService {

    /** 默认单屏卡片数量。 */
    private static final int DEFAULT_FEED_SIZE = 10;
    /** 单次请求最大允许拉取上限。 */
    private static final int MAX_FEED_SIZE = 50;
    /** 同作者卡片之间最小物理间隔数。 */
    private static final int AUTHOR_DE_DUP_GAP = 2;
    /** 单个召回通道并行执行超时上限 (毫秒)。 */
    private static final long RECALL_TIMEOUT_MS = 500L;

    /**
     * 预置槽位交织模板 (10 个卡片一个周期：50% 核心个性化、30% 探索破圈、10% 近期高热、10% 关注互动)。
     */
    private static final List<String> DEFAULT_SLOT_PATTERN = List.of(
            "PERSONALIZED", "FOLLOWING", "PERSONALIZED", "EXPLORE",
            "TRENDING", "PERSONALIZED", "EXPLORE", "PERSONALIZED",
            "EXPLORE", "PERSONALIZED"
    );

    private final UserProfileRepository userProfileRepository;
    private final UserBlockRepository userBlockRepository;
    private final CandidateVideoRepository candidateVideoRepository;
    private final List<RecommendRecallChannel> recallChannels;
    private final Executor recallExecutor;

    /**
     * Spring 依赖注入主构造器：基于 OCP 注入容器中所有多路召回通道契约实现。
     *
     * @param userProfileRepository 用户画像持久化仓储
     * @param userBlockRepository 用户黑名单屏蔽持久化仓储
     * @param candidateVideoRepository 候选视频持久化仓储
     * @param recallChannels Spring 容器中注册的所有召回通道契约实现列表
     */
    @Autowired
    public RecommendFeedApplicationService(
            UserProfileRepository userProfileRepository,
            UserBlockRepository userBlockRepository,
            CandidateVideoRepository candidateVideoRepository,
            List<RecommendRecallChannel> recallChannels) {
        this(userProfileRepository, userBlockRepository, candidateVideoRepository, recallChannels, Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 全参构造器：支持外部定制异步并发调度执行器。
     *
     * @param userProfileRepository 用户画像持久化仓储
     * @param userBlockRepository 用户黑名单屏蔽持久化仓储
     * @param candidateVideoRepository 候选视频持久化仓储
     * @param recallChannels 召回通道契约实现列表
     * @param recallExecutor 召回异步并发执行器 (可为 null，默认采用虚拟线程执行器)
     */
    public RecommendFeedApplicationService(
            UserProfileRepository userProfileRepository,
            UserBlockRepository userBlockRepository,
            CandidateVideoRepository candidateVideoRepository,
            List<RecommendRecallChannel> recallChannels,
            Executor recallExecutor) {
        this.userProfileRepository = userProfileRepository;
        this.userBlockRepository = userBlockRepository;
        this.candidateVideoRepository = candidateVideoRepository;
        this.recallChannels = recallChannels != null ? new ArrayList<>(recallChannels) : new ArrayList<>();
        this.recallExecutor = recallExecutor != null ? recallExecutor : Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 兼容便利构造器：方便旧测试用例或特定场景显式注入 4 个基础推荐通道。
     */
    public RecommendFeedApplicationService(
            UserProfileRepository userProfileRepository,
            UserBlockRepository userBlockRepository,
            CandidateVideoRepository candidateVideoRepository,
            PersonalizedRecallChannel personalizedRecallChannel,
            ExploreRecallChannel exploreRecallChannel,
            TrendingRecallChannel trendingRecallChannel,
            FollowingRecallChannel followingRecallChannel) {
        this(userProfileRepository, userBlockRepository, candidateVideoRepository,
                buildChannelList(personalizedRecallChannel, exploreRecallChannel, trendingRecallChannel, followingRecallChannel),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    private static List<RecommendRecallChannel> buildChannelList(
            PersonalizedRecallChannel personalizedRecallChannel,
            ExploreRecallChannel exploreRecallChannel,
            TrendingRecallChannel trendingRecallChannel,
            FollowingRecallChannel followingRecallChannel) {
        List<RecommendRecallChannel> channels = new ArrayList<>();
        if (personalizedRecallChannel != null) channels.add(personalizedRecallChannel);
        if (exploreRecallChannel != null) channels.add(exploreRecallChannel);
        if (trendingRecallChannel != null) channels.add(trendingRecallChannel);
        if (followingRecallChannel != null) channels.add(followingRecallChannel);
        return channels;
    }

    /**
     * 获取首页多路混合瀑布流推荐。
     *
     * @param userId 操作用户账号 ID (可为 null，代表游客未登录态)
     * @param size 请求期望获取的推荐数量
     * @return 编排打散后的最终推荐结果
     */
    public RecommendFeedResult getPersonalizedFeed(String userId, int size) {
        // 步骤 1：入参规整化与边界收口
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

        BlockedTargets blockedTargets = extractBlockedTargets(userBlocks);

        RecallContext context = RecallContext.builder()
                .userId(cleanUserId)
                .userProfile(userProfile)
                .userBlocks(userBlocks)
                .targetTotalSize(targetSize)
                .build();

        // 步骤 3：多路并行召回 (带超时控制与单个通道异常熔断隔离)
        Map<String, List<RecalledCandidate>> rawPools = parallelRecall(context, targetSize);

        // 步骤 4：四道统一硬门禁前置过滤 (状态、自斥、拉黑、近期已看)，构建纯净合规物料队列
        Map<String, Queue<RecalledCandidate>> compliantPools = filterChannelPools(
                rawPools, cleanUserId, userProfile, blockedTargets
        );

        // 步骤 5：构建通道降级吸收优先级序列 (优先按通道配比权重降序排列)
        List<String> fallbackPriorityOrder = buildFallbackPriorityOrder();

        // 步骤 6：槽位交织模板混合编排与自适应降级吸收 (无缝填槽，不足时冷启动保底)
        List<RecalledCandidate> blendedItems = blendSlotsWithFallback(
                targetSize, compliantPools, fallbackPriorityOrder, cleanUserId, userProfile, blockedTargets
        );

        // 步骤 7：多样性打散重排 (同作者物理间隔 >= 2)
        List<RecalledCandidate> reRankedItems = reRankWithDiversity(blendedItems, targetSize);

        // 步骤 8：截断组装应用层结果 DTO
        List<RecommendItemResult> itemResults = reRankedItems.stream()
                .map(rc -> new RecommendItemResult(
                        rc.getCandidate().getVid(),
                        roundScore(rc.getScore()),
                        rc.getChannel(),
                        rc.getReason()
                ))
                .toList();

        boolean hasMore = blendedItems.size() > reRankedItems.size();
        return new RecommendFeedResult(itemResults, hasMore);
    }

    /**
     * 并发调度各激活召回通道，实施全局统一超时控制与单通道异常熔断隔离。
     *
     * <p>性能与可靠性设计：
     * <ul>
     *   <li><b>全通道并发拉取</b>：各支持通道由虚拟线程异步拉取，消除串行累加时延；</li>
     *   <li><b>全局硬上限超时</b>：通过 {@link CompletableFuture#allOf} 实施全局统一超时（{@value #RECALL_TIMEOUT_MS}ms），
     *       杜绝在单通道循环中逐个调用 {@code get(timeout)} 造成的级联超时累加隐患；</li>
     *   <li><b>非阻塞收拢与安全熔断</b>：超时或异常的慢通道直接标记取消并降级为空列表，已正常返回的通道物料顺利进入后续流水线。</li>
     * </ul>
     * </p>
     */
    private Map<String, List<RecalledCandidate>> parallelRecall(RecallContext context, int targetSize) {
        if (recallChannels.isEmpty()) {
            return Collections.emptyMap();
        }

        // 步骤 3.1：根据上下文支持度派发并发异步任务
        Map<String, CompletableFuture<List<RecalledCandidate>>> futureMap = new LinkedHashMap<>();

        for (RecommendRecallChannel channel : recallChannels) {
            String channelName = channel.getChannelName() != null ? channel.getChannelName() : channel.getClass().getSimpleName();

            // 前置短路过滤：不满足上下文条件直接跳过（避免无效线程开销）
            try {
                if (!channel.supports(context)) {
                    log.debug("召回通道前置条件不满足，跳过执行: channel={}", channelName);
                    continue;
                }
            } catch (Exception ex) {
                log.warn("召回通道前置 supports 检查异常，跳过执行: channel={}, error={}", channelName, ex.getMessage());
                continue;
            }

            // 计算该通道期望召回配额 (放大 2 倍以抵御后续硬过滤淘汰)
            int ratio = channel.getTargetRatioPercentage() > 0 ? channel.getTargetRatioPercentage() : 10;
            int expectedCount = Math.max(1, (int) Math.round(targetSize * (ratio / 100.0)));
            int fetchQuota = Math.max(expectedCount * 2, 4);

            CompletableFuture<List<RecalledCandidate>> future = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            List<RecalledCandidate> candidates = channel.recall(context, fetchQuota);
                            return candidates != null ? candidates : Collections.<RecalledCandidate>emptyList();
                        } catch (Exception ex) {
                            log.warn("召回通道执行异常，降级为空列表: channel={}, error={}", channelName, ex.getMessage(), ex);
                            return Collections.<RecalledCandidate>emptyList();
                        }
                    },
                    recallExecutor
            );
            futureMap.put(channelName, future);
        }

        if (futureMap.isEmpty()) {
            return Collections.emptyMap();
        }

        // 步骤 3.2：通过 allOf 实施全局统一超时等待，防止循环逐个超时累加导致主线程阻塞失控
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futureMap.values().toArray(new CompletableFuture[0])
        );

        try {
            allOf.get(RECALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("多路并发召回触发全局超时({}ms)，未完成的慢通道将被熔断弃用", RECALL_TIMEOUT_MS);
        } catch (Exception ex) {
            log.warn("多路并发召回全局等待异常: error={}", ex.getMessage());
        }

        // 步骤 3.3：非阻塞提取各通道结果，超时或异常通道安全取消并降级为空列表
        Map<String, List<RecalledCandidate>> rawPools = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<List<RecalledCandidate>>> entry : futureMap.entrySet()) {
            String channelName = entry.getKey();
            CompletableFuture<List<RecalledCandidate>> future = entry.getValue();

            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    List<RecalledCandidate> list = future.join();
                    rawPools.put(channelName, list != null ? list : Collections.emptyList());
                } catch (Exception ex) {
                    log.warn("召回通道提取结果异常，降级为空: channel={}, error={}", channelName, ex.getMessage());
                    rawPools.put(channelName, Collections.emptyList());
                }
            } else {
                future.cancel(true);
                log.warn("召回通道未在限定时效内完成或执行异常，安全熔断为空: channel={}", channelName);
                rawPools.put(channelName, Collections.emptyList());
            }
        }

        return rawPools;
    }

    /**
     * 对多路原始物料执行四道硬过滤门禁，构建纯净合规队列池。
     */
    private Map<String, Queue<RecalledCandidate>> filterChannelPools(
            Map<String, List<RecalledCandidate>> rawPools,
            String userId,
            UserProfile userProfile,
            BlockedTargets blockedTargets) {

        Map<String, Queue<RecalledCandidate>> compliantPools = new LinkedHashMap<>();

        for (Map.Entry<String, List<RecalledCandidate>> entry : rawPools.entrySet()) {
            String channelName = entry.getKey();
            Queue<RecalledCandidate> queue = new ArrayDeque<>();
            Set<String> channelSeenVids = new HashSet<>();

            for (RecalledCandidate rc : entry.getValue()) {
                if (rc == null || rc.getCandidate() == null) {
                    continue;
                }
                CandidateVideo cv = rc.getCandidate();
                if (channelSeenVids.add(cv.getVid()) && isCandidatePermitted(cv, userId, userProfile, blockedTargets)) {
                    queue.offer(rc);
                }
            }
            compliantPools.put(channelName, queue);
        }

        return compliantPools;
    }

    /**
     * 槽位交织混合编排 (Slot Blending) 与降级吸收：
     * 按预置槽位模板依次挑选合规物料，若通道物料不足自动顺位吸收，全部耗尽时从最新活跃候选池保底补齐。
     */
    private List<RecalledCandidate> blendSlotsWithFallback(
            int targetSize,
            Map<String, Queue<RecalledCandidate>> compliantPools,
            List<String> fallbackPriorityOrder,
            String userId,
            UserProfile userProfile,
            BlockedTargets blockedTargets) {

        List<RecalledCandidate> selected = new ArrayList<>();
        Set<String> selectedVids = new HashSet<>();

        // 放大混合抓取配额以供多样性重排充分打散 (同作者间隔 >= 2)
        int blendTargetCount = Math.max(targetSize * 2, 20);

        for (int i = 0; i < blendTargetCount; i++) {
            String preferredChannel = DEFAULT_SLOT_PATTERN.get(i % DEFAULT_SLOT_PATTERN.size());
            RecalledCandidate candidate = pickCandidate(preferredChannel, selectedVids, compliantPools, fallbackPriorityOrder);
            if (candidate != null) {
                selected.add(candidate);
            } else {
                // 若所有通道均已无可用物料，提前跳出槽位交织
                boolean hasRemaining = compliantPools.values().stream().anyMatch(q -> !q.isEmpty());
                if (!hasRemaining) {
                    break;
                }
            }
        }

        // 若通道物料耗尽仍未达到期望展示条数 targetSize，从最新活跃候选池保底补齐
        if (selected.size() < targetSize) {
            List<CandidateVideo> fallbacks = candidateVideoRepository.findRecentActive(targetSize * 2);
            LocalDateTime now = LocalDateTime.now();
            if (fallbacks != null) {
                for (CandidateVideo cv : fallbacks) {
                    if (selected.size() >= targetSize) {
                        break;
                    }
                    if (cv != null && cv.getVid() != null && !selectedVids.contains(cv.getVid())) {
                        if (isCandidatePermitted(cv, userId, userProfile, blockedTargets)) {
                            selectedVids.add(cv.getVid());
                            long hoursAgo = (cv.getPublishedAt() != null)
                                    ? Math.max(0, Duration.between(cv.getPublishedAt(), now).toHours())
                                    : 0;
                            double score = 1.0 / (1.0 + hoursAgo / 24.0);
                            selected.add(new RecalledCandidate(cv, "COLD_START", score, "新鲜发布"));
                        }
                    }
                }
            }
        }

        return selected;
    }

    /**
     * 从合规物料队列中按照首选通道挑取卡片；若首选队列为空，按降级优先级顺位轮询顶替。
     */
    private RecalledCandidate pickCandidate(
            String preferredChannel,
            Set<String> selectedVids,
            Map<String, Queue<RecalledCandidate>> compliantPools,
            List<String> fallbackPriorityOrder) {

        // 步骤 1：优先尝试从该槽位指定的通道队列提取物料
        Queue<RecalledCandidate> preferredQueue = compliantPools.get(preferredChannel);
        RecalledCandidate candidate = pollUnused(preferredQueue, selectedVids);
        if (candidate != null) {
            return candidate;
        }

        // 步骤 2：首选通道物料不足，按优先级顺位从其他可用通道吸收顶替
        for (String fallbackChannel : fallbackPriorityOrder) {
            if (fallbackChannel.equals(preferredChannel)) {
                continue;
            }
            Queue<RecalledCandidate> fallbackQueue = compliantPools.get(fallbackChannel);
            candidate = pollUnused(fallbackQueue, selectedVids);
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * 从指定物料队列中弹出首个未被全局选中的物料，自动剔除队列内已被其他通道选走的重复项。
     */
    private RecalledCandidate pollUnused(Queue<RecalledCandidate> queue, Set<String> selectedVids) {
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        while (!queue.isEmpty()) {
            RecalledCandidate item = queue.poll();
            if (item != null && item.getCandidate() != null) {
                String vid = item.getCandidate().getVid();
                if (vid != null && selectedVids.add(vid)) {
                    return item;
                }
            }
        }
        return null;
    }

    /**
     * 检查候选视频物料是否满足平台四道统一硬门禁约束。
     *
     * @param cv 候选视频领域实体
     * @param userId 当前用户 ID (可为 null)
     * @param userProfile 用户画像实体 (可为 null)
     * @param blockedTargets 已屏蔽视频、作者、话题黑名单集合
     * @return true 表示合规可通过，false 表示被硬门禁一票否决
     */
    private boolean isCandidatePermitted(
            CandidateVideo cv,
            String userId,
            UserProfile userProfile,
            BlockedTargets blockedTargets) {

        if (cv == null) {
            return false;
        }

        // 门禁 1：物料状态必须为 ACTIVE (可推荐态)
        if (!cv.isRecommendable()) {
            return false;
        }

        // 门禁 2：不可推荐创作者本人发布的作品
        if (userId != null && userId.equals(cv.getAuthorId())) {
            return false;
        }

        // 门禁 3：用户明确拉黑/屏蔽门禁 (针对视频、作者、话题)
        if (blockedTargets.vids().contains(cv.getVid())) {
            return false;
        }
        if (blockedTargets.authors().contains(cv.getAuthorId())) {
            return false;
        }
        if (hasIntersection(parseCommaTags(cv.getTopicTagIds()), blockedTargets.topics())) {
            return false;
        }

        // 门禁 4：近期已看曝光历史去重 (避免短期重复推荐打扰)
        if (userProfile != null && userProfile.hasWatchedRecently(cv.getVid())) {
            return false;
        }

        return true;
    }

    /**
     * 多样性滑动窗口打散重排：保证同作者物料在最终输出流中至少间隔 AUTHOR_DE_DUP_GAP 个卡片。
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

    private List<String> buildFallbackPriorityOrder() {
        List<String> priorityOrder = recallChannels.stream()
                .sorted(Comparator.comparingInt(RecommendRecallChannel::getTargetRatioPercentage).reversed())
                .map(RecommendRecallChannel::getChannelName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (priorityOrder.isEmpty()) {
            return List.of("PERSONALIZED", "EXPLORE", "TRENDING", "FOLLOWING");
        }
        return priorityOrder;
    }

    private BlockedTargets extractBlockedTargets(List<UserBlock> userBlocks) {
        Set<String> vids = new HashSet<>();
        Set<String> authors = new HashSet<>();
        Set<String> topics = new HashSet<>();

        if (userBlocks != null) {
            for (UserBlock block : userBlocks) {
                if (block == null || block.getBlockType() == null || block.getTargetId() == null) {
                    continue;
                }
                switch (block.getBlockType()) {
                    case VIDEO -> vids.add(block.getTargetId());
                    case AUTHOR -> authors.add(block.getTargetId());
                    case TOPIC -> topics.add(block.getTargetId());
                }
            }
        }
        return new BlockedTargets(vids, authors, topics);
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

    /**
     * 用户黑名单屏蔽目标规整化封装 Record。
     */
    private record BlockedTargets(Set<String> vids, Set<String> authors, Set<String> topics) {}
}

