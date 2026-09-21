package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.application.dto.RecommendFeedResult;
import com.calles.platform.recommend.application.dto.RecommendItemResult;
import com.calles.platform.recommend.config.RecommendFeedBufferProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 推荐流待看缓冲池门面服务 (RecommendFeedBufferService)。
 *
 * <p>核心职责与架构定位：
 * <ul>
 *   <li><b>大包预生成与轻量消费</b>：单次协同底层编排服务生成 30~50 条大批次物料，前端请求时直接从用户专享 Redis 队列 LPOP，耗时降至 &lt; 5ms；</li>
 *   <li><b>低水位静默异步补水</b>：实时监控待看池剩余长度，当剩余物料 &le; 15 条时，通过虚拟线程异步静默补水，杜绝刷屏白屏卡顿；</li>
 *   <li><b>防重入分布式并发锁</b>：快速翻页时通过 Redis SETNX 锁控制后台补水并发度，防止重复计算与队列暴涨；</li>
 *   <li><b>高可用故障熔断（Fail-Open）</b>：Redis 离线或异常时自动平滑回退至实时计算，确保对外推荐 API 100% 可用；</li>
 *   <li><b>游客态免池化穿透</b>：未登录用户直接实时召回计算，不占用 Redis 用户缓存。</li>
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

    private final RecommendFeedApplicationService recommendFeedApplicationService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RecommendFeedBufferProperties properties;
    private final Executor refillExecutor;

    @Autowired
    public RecommendFeedBufferService(
            RecommendFeedApplicationService recommendFeedApplicationService,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            RecommendFeedBufferProperties properties) {
        this(recommendFeedApplicationService, stringRedisTemplate, objectMapper, properties,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    public RecommendFeedBufferService(
            RecommendFeedApplicationService recommendFeedApplicationService,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            RecommendFeedBufferProperties properties,
            Executor refillExecutor) {
        this.recommendFeedApplicationService = recommendFeedApplicationService;
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

            // 步骤 4：命中缓存且剩余长度触达低水位线，触发虚拟线程后台静默补水
            if (currentRemaining <= properties.getLowWatermark()) {
                triggerAsyncRefill(cleanUserId, currentRemaining);
            }

            boolean hasMore = currentRemaining > 0 || poppedItems.size() >= targetSize;
            return new RecommendFeedResult(poppedItems, hasMore);

        } catch (Exception ex) {
            // 步骤 5：Redis 故障平滑熔断降级：现场计算保底，保障核心链路不停摆
            log.warn("访问 Redis 推荐待看缓冲池异常，平滑降级实时计算: userId={}, error={}", cleanUserId, ex.getMessage());
            return recommendFeedApplicationService.getPersonalizedFeed(cleanUserId, targetSize);
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
