package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.application.dto.RecommendFeedResult;
import com.calles.platform.recommend.application.dto.RecommendItemResult;
import com.calles.platform.recommend.config.RecommendFeedBufferProperties;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.repository.CandidateVideoRepository;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 推荐流待看缓冲池门面服务 (RecommendFeedBufferService)。
 *
 * <p>核心职责与架构定位：
 * <ul>
 *   <li><b>大包预生成与轻量消费</b>：单次协同底层编排服务生成 30~50 条大批次物料，前端请求时直接从用户专享 Redis 队列 LPOP，耗时降至 &lt; 5ms；</li>
 *   <li><b>低水位静默异步补水</b>：实时监控待看池剩余长度，当剩余物料 &le; 15 条时，通过虚拟线程异步静默补水，杜绝刷屏白屏卡顿；</li>
 *   <li><b>防重入分布式并发锁</b>：快速翻页时通过 Redis SETNX 锁控制后台补水并发度，防止重复计算与队列暴涨；</li>
 *   <li><b>高可用故障熔断（Fail-Open）</b>：Redis 离线或异常时自动平滑回退至实时计算，确保对外推荐 API 100% 可用；</li>
 *   <li><b>游客态免池化穿透</b>：未登录用户直接实时召回计算，不占用 Redis 用户缓存；</li>
 *   <li><b>出队物料复核（REC-02）</b>：批量检查候选状态和用户屏蔽，过滤已下架/封禁/拉黑的物料，有限补取或降级实时计算。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class RecommendFeedBufferService {

    /** Redis 推荐待看缓冲队列 Key 前缀 (命名空间单业务隔离)。 */
    public static final String BUFFER_KEY_PREFIX = "recommend:feed:buffer:";

    /** 异步补水防重入分布式锁 Key 前缀。 */
    public static final String REFILL_LOCK_PREFIX = "recommend:feed:refill:lock:";

    /** 单次请求最大允许拉取上限。 */
    private static final int MAX_FEED_SIZE = 50;

    /** 有效率低于此阈值时触发补取。 */
    private static final double VALID_RATE_THRESHOLD = 0.5;

    private final RecommendFeedApplicationService recommendFeedApplicationService;
    private final CandidateVideoRepository candidateVideoRepository;
    private final UserBlockRepository userBlockRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RecommendFeedBufferProperties properties;
    private final Executor refillExecutor;

    @Autowired
    public RecommendFeedBufferService(
            RecommendFeedApplicationService recommendFeedApplicationService,
            CandidateVideoRepository candidateVideoRepository,
            UserBlockRepository userBlockRepository,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            RecommendFeedBufferProperties properties) {
        this(recommendFeedApplicationService, candidateVideoRepository, userBlockRepository,
                stringRedisTemplate, objectMapper, properties,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    public RecommendFeedBufferService(
            RecommendFeedApplicationService recommendFeedApplicationService,
            CandidateVideoRepository candidateVideoRepository,
            UserBlockRepository userBlockRepository,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            RecommendFeedBufferProperties properties,
            Executor refillExecutor) {
        this.recommendFeedApplicationService = recommendFeedApplicationService;
        this.candidateVideoRepository = candidateVideoRepository;
        this.userBlockRepository = userBlockRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties != null ? properties : new RecommendFeedBufferProperties();
        this.refillExecutor = refillExecutor != null ? refillExecutor : Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 从推荐待看缓冲池中分批获取推荐物料。
     *
     * @param userId 操作用户 ID (为 null 代表未登录游客)
     * @param size 请求拉取的期望物料数量
     * @return 推荐卡片结果与是否还有更多卡片的标识
     */
    public RecommendFeedResult consumeFeed(String userId, int size) {
        // 步骤 1：入参规格化与特性开关检查
        int targetSize = (size <= 0) ? properties.getDefaultPopSize() : Math.min(size, MAX_FEED_SIZE);

        boolean isLogin = (userId != null && !userId.isBlank());
        if (!properties.isEnabled() || !isLogin) {
            // 开关关闭或游客用户：直接穿透走实时计算
            return recommendFeedApplicationService.getPersonalizedFeed(userId, targetSize);
        }

        String cleanUserId = userId.trim();
        String bufferKey = BUFFER_KEY_PREFIX + cleanUserId;

        try {
            // 步骤 2：尝试从 Redis 待看池队头弹出指定数量的物料
            List<String> rawPopped = stringRedisTemplate.opsForList().leftPop(bufferKey, targetSize);
            List<RecommendItemResult> poppedItems = deserializeItems(rawPopped);

            Long remainingCount = stringRedisTemplate.opsForList().size(bufferKey);
            long currentRemaining = (remainingCount != null) ? remainingCount : 0;

            // 步骤 3：冷启动处理：待看池为空时现场计算大包（如 30 条），首批交付用户，剩余回填缓冲池
            if (poppedItems.isEmpty()) {
                log.debug("用户推荐待看池为空，触发大包冷启动计算: userId={}, targetSize={}", cleanUserId, targetSize);
                RecommendFeedResult freshFeed = recommendFeedApplicationService.getPersonalizedFeed(
                        cleanUserId, properties.getBatchGenerateSize()
                );
                if (freshFeed == null || freshFeed.getItems().isEmpty()) {
                    return new RecommendFeedResult(Collections.emptyList(), false);
                }

                List<RecommendItemResult> allItems = freshFeed.getItems();
                int returnCount = Math.min(allItems.size(), targetSize);
                List<RecommendItemResult> directReturn = allItems.subList(0, returnCount);
                List<RecommendItemResult> toBuffer = allItems.subList(returnCount, allItems.size());

                if (!toBuffer.isEmpty()) {
                    pushToBuffer(bufferKey, toBuffer);
                }

                boolean hasMore = allItems.size() > returnCount;
                return new RecommendFeedResult(directReturn, hasMore);
            }

            // 步骤 4（REC-02）：批量复核出队物料的候选状态和用户屏蔽
            List<RecommendItemResult> effectiveItems = validateAndFilterItems(poppedItems, cleanUserId);
            int filteredCount = poppedItems.size() - effectiveItems.size();
            if (filteredCount > 0) {
                log.info("缓冲物料复核过滤: userId={}, total={}, filtered={}, effective={}",
                        cleanUserId, poppedItems.size(), filteredCount, effectiveItems.size());
            }

            // 步骤 5：有效率过低时补取一轮缓存或降级实时计算
            if (effectiveItems.size() < targetSize * VALID_RATE_THRESHOLD && currentRemaining > 0) {
                log.debug("有效物料不足，尝试补取一轮: userId={}, effective={}, target={}",
                        cleanUserId, effectiveItems.size(), targetSize);
                int needed = targetSize - effectiveItems.size();
                List<RecommendItemResult> refilled = attemptRefillOnce(cleanUserId, needed, bufferKey);
                effectiveItems.addAll(refilled);

                // 更新剩余计数
                Long updatedRemaining = stringRedisTemplate.opsForList().size(bufferKey);
                currentRemaining = (updatedRemaining != null) ? updatedRemaining : 0;
            }

            // 步骤 6：若仍然为空，降级到实时计算
            if (effectiveItems.isEmpty()) {
                log.warn("缓冲物料复核后全部失效，降级实时计算: userId={}", cleanUserId);
                return recommendFeedApplicationService.getPersonalizedFeed(cleanUserId, targetSize);
            }

            // 步骤 7：命中缓存且剩余长度触达低水位线，触发虚拟线程后台静默补水
            if (currentRemaining <= properties.getLowWatermark()) {
                triggerAsyncRefill(cleanUserId, currentRemaining);
            }

            // 步骤 8：截取目标数量并返回
            int actualReturnSize = Math.min(effectiveItems.size(), targetSize);
            List<RecommendItemResult> finalItems = effectiveItems.subList(0, actualReturnSize);
            // 满额返回代表仍可继续加载，队列暂时为空不能证明实时候选已经耗尽。
            boolean hasMore = currentRemaining > 0 || actualReturnSize == targetSize
                    || effectiveItems.size() > actualReturnSize;
            return new RecommendFeedResult(finalItems, hasMore);

        } catch (Exception ex) {
            // 步骤 9：Redis 故障平滑熔断降级：现场计算保底，保障核心链路不停摆
            log.warn("访问 Redis 推荐待看缓冲池异常，平滑降级实时计算: userId={}, error={}", cleanUserId, ex.getMessage());
            return recommendFeedApplicationService.getPersonalizedFeed(cleanUserId, targetSize);
        }
    }

    /**
     * 复核出队物料：批量检查候选状态 + 用户屏蔽。
     *
     * @param items 待复核的推荐物料列表
     * @param userId 用户账号 ID
     * @return 过滤后的有效物料列表
     */
    private List<RecommendItemResult> validateAndFilterItems(
            List<RecommendItemResult> items, String userId) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 第一阶段：批量查候选状态（只保留 ACTIVE），同时获取 authorId
            List<String> vids = items.stream()
                    .map(RecommendItemResult::getVid)
                    .collect(Collectors.toList());
            List<CandidateVideo> activeVideos = candidateVideoRepository.findActiveByVids(vids);

            // 构建 vid -> CandidateVideo 映射，用于后续获取 authorId
            Map<String, CandidateVideo> activeVideoMap = activeVideos.stream()
                    .collect(Collectors.toMap(CandidateVideo::getVid, v -> v));

            // 第二阶段：复核所有屏蔽维度，让缓存出队与实时推荐的屏蔽规则一致。
            List<String> blockedVideoIds = userBlockRepository
                    .findTargetIdsByUserIdAndType(userId, BlockType.VIDEO);
            List<String> blockedAuthorIds = userBlockRepository
                    .findTargetIdsByUserIdAndType(userId, BlockType.AUTHOR);
            List<String> blockedTopicIds = userBlockRepository
                    .findTargetIdsByUserIdAndType(userId, BlockType.TOPIC);
            Set<String> blockedVideoSet = new HashSet<>(blockedVideoIds);
            Set<String> blockedAuthorSet = new HashSet<>(blockedAuthorIds);
            Set<String> blockedTopicSet = new HashSet<>(blockedTopicIds);

            // 第三阶段：过滤有效物料
            return items.stream()
                    .filter(item -> activeVideoMap.containsKey(item.getVid()))
                    .filter(item -> !blockedVideoSet.contains(item.getVid()))
                    .filter(item -> {
                        CandidateVideo video = activeVideoMap.get(item.getVid());
                        return video != null && !blockedAuthorSet.contains(video.getAuthorId())
                                && !hasBlockedTopic(video.getTopicTagIds(), blockedTopicSet);
                    })
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.error("复核缓冲物料异常，保守降级返回空: userId={}, error={}", userId, ex.getMessage(), ex);
            return Collections.emptyList();
        }
    }

    /**
     * 按完整标签 ID 判断主题屏蔽交集，忽略候选快照中的空标签和两端空白。
     *
     * @param topicTagIds 候选的逗号分隔主题标签，可为空
     * @param blockedTopics 用户已屏蔽的主题 ID 集合
     * @return 任一候选主题被屏蔽时返回 true
     */
    private boolean hasBlockedTopic(String topicTagIds, Set<String> blockedTopics) {
        if (topicTagIds == null || topicTagIds.isBlank() || blockedTopics.isEmpty()) {
            return false;
        }
        return Arrays.stream(topicTagIds.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .anyMatch(blockedTopics::contains);
    }

    /**
     * 从缓冲池补取一轮物料（仅限 1 次），并同样执行复核。
     *
     * @param userId 用户账号 ID
     * @param needed 需要补取的数量
     * @param bufferKey Redis 缓冲队列 Key
     * @return 复核后的有效物料列表
     */
    private List<RecommendItemResult> attemptRefillOnce(String userId, int needed, String bufferKey) {
        try {
            int fetchSize = Math.max(needed, properties.getDefaultPopSize());
            List<String> raw = stringRedisTemplate.opsForList().leftPop(bufferKey, fetchSize);
            List<RecommendItemResult> items = deserializeItems(raw);
            if (items.isEmpty()) {
                return Collections.emptyList();
            }
            // 补取的物料同样需要复核
            List<RecommendItemResult> validated = validateAndFilterItems(items, userId);
            if (!validated.isEmpty()) {
                log.debug("补取缓冲物料成功: userId={}, fetched={}, validated={}", userId, items.size(), validated.size());
            }
            return validated;
        } catch (Exception ex) {
            log.warn("补取缓冲物料异常: userId={}, error={}", userId, ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 触发虚拟线程在后台异步为用户待看缓冲池补货。
     */
    private void triggerAsyncRefill(String userId, long currentRemaining) {
        if (currentRemaining >= properties.getMaxBufferCapacity()) {
            return;
        }

        String lockKey = REFILL_LOCK_PREFIX + userId;
        // 防并发重入锁：同一时刻仅允许一个补水任务在跑
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey,
                "1",
                Duration.ofSeconds(properties.getRefillLockTimeoutSeconds())
        );

        if (Boolean.TRUE.equals(acquired)) {
            refillExecutor.execute(() -> {
                try {
                    log.debug("启动推荐待看池后台异步补水: userId={}, remaining={}", userId, currentRemaining);
                    RecommendFeedResult freshBatch = recommendFeedApplicationService.getPersonalizedFeed(
                            userId, properties.getBatchGenerateSize()
                    );
                    if (freshBatch != null && !freshBatch.getItems().isEmpty()) {
                        String bufferKey = BUFFER_KEY_PREFIX + userId;
                        pushToBuffer(bufferKey, freshBatch.getItems());
                        log.info("推荐待看池异步补水成功: userId={}, refilledCount={}", userId, freshBatch.getItems().size());
                    }
                } catch (Exception ex) {
                    log.warn("推荐待看池异步补水异常: userId={}, error={}", userId, ex.getMessage(), ex);
                } finally {
                    try {
                        stringRedisTemplate.delete(lockKey);
                    } catch (Exception ex) {
                        log.warn("释放推荐补水并发锁异常: lockKey={}, error={}", lockKey, ex.getMessage());
                    }
                }
            });
        }
    }

    /**
     * 将推荐卡片列表序列化并追加至 Redis 缓冲队列尾部 (RPUSH)。
     */
    private void pushToBuffer(String bufferKey, List<RecommendItemResult> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<String> serializedList = new ArrayList<>(items.size());
        for (RecommendItemResult item : items) {
            try {
                serializedList.add(objectMapper.writeValueAsString(item));
            } catch (Exception ex) {
                log.warn("序列化推荐卡片至缓冲池异常: vid={}, error={}", item.getVid(), ex.getMessage());
            }
        }
        if (!serializedList.isEmpty()) {
            stringRedisTemplate.opsForList().rightPushAll(bufferKey, serializedList);
            stringRedisTemplate.expire(bufferKey, Duration.ofSeconds(properties.getBufferTtlSeconds()));
        }
    }

    /**
     * 反序列化 Redis List 中存储的推荐物料 JSON 字符串列表。
     */
    private List<RecommendItemResult> deserializeItems(List<String> rawList) {
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }
        List<RecommendItemResult> list = new ArrayList<>(rawList.size());
        for (String json : rawList) {
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                RecommendItemResult item = objectMapper.readValue(json, RecommendItemResult.class);
                if (item != null && item.getVid() != null) {
                    list.add(item);
                }
            } catch (Exception ex) {
                log.warn("反序列化推荐缓冲物料 JSON 异常: json={}, error={}", json, ex.getMessage());
            }
        }
        return list;
    }
}
