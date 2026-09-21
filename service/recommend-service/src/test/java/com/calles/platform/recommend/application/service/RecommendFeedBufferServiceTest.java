package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.application.dto.RecommendFeedResult;
import com.calles.platform.recommend.application.dto.RecommendItemResult;
import com.calles.platform.recommend.config.RecommendFeedBufferProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RecommendFeedBufferService 推荐流缓冲池单元测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendFeedBufferService 推荐流缓冲池测试")
class RecommendFeedBufferServiceTest {

    @Mock
    private RecommendFeedApplicationService recommendFeedApplicationService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private RecommendFeedBufferProperties properties;
    private Executor directExecutor;
    private RecommendFeedBufferService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new RecommendFeedBufferProperties();
        properties.setEnabled(true);
        properties.setBatchGenerateSize(30);
        properties.setDefaultPopSize(10);
        properties.setLowWatermark(15);
        properties.setMaxBufferCapacity(60);
        properties.setBufferTtlSeconds(3600L);
        properties.setRefillLockTimeoutSeconds(30L);

        // 使用同步直接执行器以方便单测验证异步任务内容
        directExecutor = Runnable::run;

        lenient().when(stringRedisTemplate.opsForList()).thenReturn(listOperations);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        service = new RecommendFeedBufferService(
                recommendFeedApplicationService,
                stringRedisTemplate,
                objectMapper,
                properties,
                directExecutor
        );
    }

    @Test
    @DisplayName("缓冲池命中：直接从 Redis 队头弹出物料返回，且剩余量充足时不触发补水")
    void shouldConsumeFromBufferWhenCacheHit() throws JsonProcessingException {
        String userId = "user_hit_01";
        String bufferKey = RecommendFeedBufferService.BUFFER_KEY_PREFIX + userId;

        List<String> rawJsons = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            RecommendItemResult item = new RecommendItemResult("vid_" + i, 0.9, "PERSONALIZED", "推荐理由" + i);
            rawJsons.add(objectMapper.writeValueAsString(item));
        }

        // 模拟 Redis 弹出 10 条
        when(listOperations.leftPop(bufferKey, 10)).thenReturn(rawJsons);
        // 模拟剩余 25 条 (高于低水位线 15)
        when(listOperations.size(bufferKey)).thenReturn(25L);

        RecommendFeedResult result = service.consumeFeed(userId, 10);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(10);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_1");
        assertThat(result.isHasMore()).isTrue();

        // 验证未调用底层复杂编排计算，也未加分布式锁补水
        verify(recommendFeedApplicationService, never()).getPersonalizedFeed(anyString(), anyInt());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("低水位静默补水：消费后剩余物料低于水位线(15)时，后台自动异步触发生成 30 条物料入池")
    void shouldTriggerAsyncRefillWhenReachingLowWatermark() throws JsonProcessingException {
        String userId = "user_refill_01";
        String bufferKey = RecommendFeedBufferService.BUFFER_KEY_PREFIX + userId;
        String lockKey = RecommendFeedBufferService.REFILL_LOCK_PREFIX + userId;

        List<String> rawJsons = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            RecommendItemResult item = new RecommendItemResult("vid_" + i, 0.9, "PERSONALIZED", "理由");
            rawJsons.add(objectMapper.writeValueAsString(item));
        }

        when(listOperations.leftPop(bufferKey, 10)).thenReturn(rawJsons);
        // 模拟剩余 8 条 (<= 15 触发低水位)
        when(listOperations.size(bufferKey)).thenReturn(8L);
        // 模拟成功获取分布式锁
        when(valueOperations.setIfAbsent(eq(lockKey), eq("1"), any(Duration.class))).thenReturn(true);

        // 模拟后台重新计算出 30 条新物料
        List<RecommendItemResult> refilledBatch = new ArrayList<>();
        for (int i = 101; i <= 130; i++) {
            refilledBatch.add(new RecommendItemResult("vid_" + i, 0.85, "TRENDING", "补水理由"));
        }
        when(recommendFeedApplicationService.getPersonalizedFeed(userId, 30))
                .thenReturn(new RecommendFeedResult(refilledBatch, true));

        RecommendFeedResult result = service.consumeFeed(userId, 10);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(10);

        // 验证触发了底层计算与追加写
        verify(recommendFeedApplicationService).getPersonalizedFeed(userId, 30);
        verify(listOperations).rightPushAll(eq(bufferKey), anyList());
        verify(stringRedisTemplate).delete(lockKey);
    }

    @Test
    @DisplayName("冷启动/缓存未命中：待看池为空时，同步生成 30 条，首批 10 条返回，剩余 20 条写入缓冲池")
    void shouldFallbackToSyncComputeAndBufferWhenCacheMiss() {
        String userId = "user_cold_start";
        String bufferKey = RecommendFeedBufferService.BUFFER_KEY_PREFIX + userId;

        when(listOperations.leftPop(bufferKey, 10)).thenReturn(Collections.emptyList());

        // 现场大包计算 30 条
        List<RecommendItemResult> batch = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            batch.add(new RecommendItemResult("vid_" + i, 0.95 - i * 0.01, "PERSONALIZED", "冷启动精选"));
        }
        when(recommendFeedApplicationService.getPersonalizedFeed(userId, 30))
                .thenReturn(new RecommendFeedResult(batch, true));

        RecommendFeedResult result = service.consumeFeed(userId, 10);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(10);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_1");
        assertThat(result.getItems().get(9).getVid()).isEqualTo("vid_10");
        assertThat(result.isHasMore()).isTrue();

        // 验证剩余 20 条回填进 Redis
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<String>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(listOperations).rightPushAll(eq(bufferKey), captor.capture());
        assertThat(captor.getValue()).hasSize(20);
        verify(stringRedisTemplate).expire(eq(bufferKey), eq(Duration.ofSeconds(3600)));
    }

    @Test
    @DisplayName("游客用户免池化：userId 为空时直接透传走实时编排，不写入或读取 Redis")
    void shouldBypassBufferForGuestUser() {
        when(recommendFeedApplicationService.getPersonalizedFeed(null, 10))
                .thenReturn(new RecommendFeedResult(List.of(
                        new RecommendItemResult("vid_guest", 0.9, "TRENDING", "全站热点")
                ), false));

        RecommendFeedResult result = service.consumeFeed(null, 10);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_guest");

        // 验证 Redis 未发生任何交互
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("Redis 故障平滑熔断降级：Redis 离线或异常时自动无感降级为实时计算，核心接口不停摆")
    void shouldGracefullyFallbackToRealtimeComputeOnRedisException() {
        String userId = "user_redis_fail";

        // 模拟 Redis 抛出网络拒绝/连接中断异常
        when(listOperations.leftPop(anyString(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("Redis connection refused: localhost:6379"));

        when(recommendFeedApplicationService.getPersonalizedFeed(userId, 10))
                .thenReturn(new RecommendFeedResult(List.of(
                        new RecommendItemResult("vid_degraded", 0.88, "COLD_START", "降级兜底")
                ), false));

        RecommendFeedResult result = service.consumeFeed(userId, 10);

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getVid()).isEqualTo("vid_degraded");

        // 验证平滑降级调用了实时计算
        verify(recommendFeedApplicationService).getPersonalizedFeed(userId, 10);
    }
}
