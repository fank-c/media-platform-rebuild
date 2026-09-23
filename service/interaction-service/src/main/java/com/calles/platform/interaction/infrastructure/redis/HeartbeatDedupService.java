package com.calles.platform.interaction.infrastructure.redis;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 视频播放心跳与播放量防刷去重服务。
 *
 * <p>通过时间窗口（默认 30 分钟）限制同一用户或会话针对同一视频重复计费有效播放量。</p>
 */
@Slf4j
@Component
public class HeartbeatDedupService {

    /** 防刷窗口时长：30 分钟。 */
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(30);

    /** 本地内存兜底缓存（当 Redis 不可用或测试环境无连接时）。 */
    private final ConcurrentHashMap<String, Long> localFallbackMap = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;

    public HeartbeatDedupService(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试记录单窗口有效播放并判定是否属于首次播放。
     *
     * @param userId 用户账号 ID
     * @param vid 视频业务公开短码
     * @return true 若为新窗口首次有效播放（应当累加播放量计数）；false 若处于去重窗口期内（跳过递增）
     */
    public boolean tryAcquireFirstPlay(String userId, String vid) {
        String key = "int:watch:dedup:" + vid + ":" + userId;

        if (redisTemplate != null) {
            try {
                Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "1", DEDUP_WINDOW);
                return Boolean.TRUE.equals(success);
            } catch (Exception e) {
                log.warn("Redis 去重连接异常，降级使用内存防刷判定: {}", e.getMessage());
            }
        }

        // 降级使用本地内存时间戳判定
        long now = System.currentTimeMillis();
        long windowMillis = DEDUP_WINDOW.toMillis();
        Long lastSeen = localFallbackMap.get(key);
        if (lastSeen == null || now - lastSeen > windowMillis) {
            localFallbackMap.put(key, now);
            return true;
        }
        return false;
    }
}
