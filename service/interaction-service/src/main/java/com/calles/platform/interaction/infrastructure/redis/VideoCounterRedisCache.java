package com.calles.platform.interaction.infrastructure.redis;

import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 视频互动统计 Redis 缓存服务 (Write-Behind 缓冲与读拦截)。
 *
 * <p>核心机制：
 * <ul>
 *   <li>写操作：直接在 Redis Hash 执行 {@code HINCRBY} 原子指令，并将变动的 {@code vid} 标记至脏集合，同时滑动续期；</li>
 *   <li>读操作：优先从 Redis 读取，命中的热点视频自动触发滑动续期（延长 TTL）；未命中时冷加载并回写缓存；</li>
 *   <li>快速淘汰：冷视频在 TTL 到期后由 Redis 自动释放内存，避免常驻空间浪费；</li>
 *   <li>容错降级：当 Redis 未配置或网络异常时，透明降级至本地内存缓存，确保高可用。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class VideoCounterRedisCache {

    /** Redis 视频计数 Hash 键前缀，完整键形如：int:counter:{vid}。 */
    private static final String COUNTER_KEY_PREFIX = "int:counter:";

    /** 记录存在未持久化增量变动的视频短码 Set 集合键名。 */
    private static final String DIRTY_SET_KEY = "int:counter:dirty";

    /** Hash 域字段名：累计播放量。 */
    private static final String FIELD_VIEW = "view";

    /** Hash 域字段名：累计有效点赞数。 */
    private static final String FIELD_LIKE = "like";

    /** Hash 域字段名：累计收藏数。 */
    private static final String FIELD_STAR = "star";

    /** Hash 域字段名：累计分享数。 */
    private static final String FIELD_SHARE = "share";

    /** 视频计数在 Redis 中的缓存过期时间，默认 30 分钟，支持配置注入。
     * -- GETTER --
     *  获取当前生效的缓存过期时间。
     *
     * @return 过期时长
     */
    @Getter
    private final Duration cacheTtl;

    /** Redis 字符串与哈希操作模板，允许为 null（用于非 Redis 依赖环境透明降级）。 */
    private final StringRedisTemplate redisTemplate;

    /** 内存降级缓存容器 (当 Redis 宕机或未配置时生效，保证服务高可用)。 */
    private final ConcurrentHashMap<String, VideoCounter> localFallbackCache = new ConcurrentHashMap<>();

    /** 本地内存脏数据标记集合，用于在降级模式下记录发生过计数变动的视频短码。 */
    private final Set<String> localDirtySet = ConcurrentHashMap.newKeySet();

    /**
     * 完整依赖注入构造方法。
     *
     * @param redisTemplate Spring Redis 操作模板（可选注入）
     * @param cacheTtl 缓存过期时间（默认 30 分钟，从配置项 interaction.counter.cache-ttl 获取）
     */
    public VideoCounterRedisCache(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${interaction.counter.cache-ttl:30m}") Duration cacheTtl) {
        this.redisTemplate = redisTemplate;
        this.cacheTtl = cacheTtl != null ? cacheTtl : Duration.ofMinutes(30);
    }

    /**
     * 对热点视频键执行滑动续期（重置 TTL）。
     *
     * @param key Redis 完整键名
     */
    private void renewTtl(String key) {
        if (redisTemplate != null) {
            try {
                redisTemplate.expire(key, cacheTtl);
            } catch (Exception e) {
                log.debug("刷新 Redis 计数 TTL 异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 原子增加播放量并标记脏数据。
     *
     * @param vid 视频公开短码
     * @param delta 播放增量（通常为正数）
     */
    public void incrementView(String vid, long delta) {
        adjustField(vid, FIELD_VIEW, delta);
    }

    /**
     * 原子调整点赞数并标记脏数据。
     *
     * @param vid 视频公开短码
     * @param delta 点赞增减量 (+1 或 -1)
     */
    public void adjustLike(String vid, long delta) {
        adjustField(vid, FIELD_LIKE, delta);
    }

    /**
     * 原子调整收藏数并标记脏数据。
     *
     * @param vid 视频公开短码
     * @param delta 收藏增减量 (+1 或 -1)
     */
    public void adjustStar(String vid, long delta) {
        adjustField(vid, FIELD_STAR, delta);
    }

    /**
     * 原子增加分享数并标记脏数据。
     *
     * @param vid 视频公开短码
     * @param delta 分享增量（必须大于 0）
     */
    public void incrementShare(String vid, long delta) {
        adjustField(vid, FIELD_SHARE, delta);
    }

    /**
     * 获取单视频互动计数（缓存优先，未命中时冷加载）。
     *
     * @param vid 视频公开短码
     * @param dbLoader 数据库冷加载回调供给者
     * @return 统计聚合根实体
     */
    public VideoCounter getCounter(String vid, Supplier<VideoCounter> dbLoader) {
        if (vid == null || vid.isBlank()) {
            return VideoCounter.createDefault(vid);
        }

        // 步骤 1: 优先尝试从 Redis Hash 中提取计数
        if (redisTemplate != null) {
            try {
                String key = COUNTER_KEY_PREFIX + vid;
                Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
                if (!entries.isEmpty()) {
                    // 步骤 1.1: 读命中触发滑动续期，确保活跃热点视频生命周期自动顺延
                    renewTtl(key);
                    return parseFromHashEntries(vid, entries);
                }
            } catch (Exception e) {
                log.warn("读取 Redis 计数异常，降级回查数据库: {}", e.getMessage());
            }
        } else {
            // 步骤 2: Redis 不可用时，检查本地内存降级缓存
            VideoCounter local = localFallbackCache.get(vid);
            if (local != null) {
                return local;
            }
        }

        // 步骤 3: 缓存均未命中，执行回调从数据库冷加载并回填缓存
        VideoCounter fromDb = dbLoader.get();
        if (fromDb == null) {
            fromDb = VideoCounter.createDefault(vid);
        }
        writeToCache(fromDb);
        return fromDb;
    }

    /**
     * 批量获取多个视频互动计数。
     *
     * <p>优先从缓存聚合数据，仅对缓存缺失的视频发起单次数据库批量查询并回填缓存，避免 N+1 查询问题。</p>
     *
     * @param vids 视频短码集合
     * @param dbBatchLoader 数据库批量加载回调函数
     * @return 统计聚合根列表
     */
    public List<VideoCounter> getBatchCounters(Collection<String> vids,
                                               Function<Collection<String>, List<VideoCounter>> dbBatchLoader) {
        if (vids == null || vids.isEmpty()) {
            return List.of();
        }

        Map<String, VideoCounter> result = new HashMap<>();
        Set<String> missingVids = new HashSet<>();

        // 步骤 1: 逐个从缓存（Redis 或本地降级缓存）中提取，未命中者记录至缺失集合
        for (String vid : vids) {
            VideoCounter cached = getFromCacheOnly(vid);
            if (cached != null) {
                result.put(vid, cached);
            } else {
                missingVids.add(vid);
            }
        }

        // 步骤 2: 针对缓存缺失的视频集合，一次性回源批量查库并回写缓存
        if (!missingVids.isEmpty()) {
            List<VideoCounter> fromDbList = dbBatchLoader.apply(missingVids);
            Map<String, VideoCounter> dbMap = new HashMap<>();
            if (fromDbList != null) {
                for (VideoCounter counter : fromDbList) {
                    dbMap.put(counter.getVid(), counter);
                    writeToCache(counter);
                }
            }
            // 步骤 3: 若 DB 仍无数据，以零值默认对象兜底填入并回写防穿透
            for (String vid : missingVids) {
                VideoCounter counter = dbMap.getOrDefault(vid, VideoCounter.createDefault(vid));
                result.put(vid, counter);
                if (!dbMap.containsKey(vid)) {
                    writeToCache(counter);
                }
            }
        }

        return new ArrayList<>(result.values());
    }

    /**
     * 从脏数据集合中弹出一批发生过变动的视频业务编码。
     *
     * @param count 提取数量上限 (如 100)
     * @return 变动的 vid 集合（若无变动返回空集合）
     */
    public Set<String> popDirtyVids(long count) {
        // 步骤 1: 优先尝试从 Redis Set 中弹出
        if (redisTemplate != null) {
            try {
                List<String> popped = redisTemplate.opsForSet().pop(DIRTY_SET_KEY, count);
                return popped != null ? new HashSet<>(popped) : Set.of();
            } catch (Exception e) {
                log.warn("从 Redis 弹出脏计数集合异常: {}", e.getMessage());
            }
        }

        // 步骤 2: Redis 不可用或发生异常时，从本地内存脏集合中安全弹出
        Set<String> batch = new HashSet<>();
        for (String vid : localDirtySet) {
            batch.add(vid);
            localDirtySet.remove(vid);
            if (batch.size() >= count) {
                break;
            }
        }
        return batch;
    }

    /**
     * 读取指定视频当前在缓存中的绝对快照（后台定时刷盘任务专用）。
     *
     * @param vid 视频公开短码
     * @return 包含当前绝对计数值的 VideoCounter 快照对象
     */
    public VideoCounter getSnapshotForFlush(String vid) {
        return getCounter(vid, () -> VideoCounter.createDefault(vid));
    }

    // ================= 私有支撑逻辑 =================

    /**
     * 底层通用字段原子调增逻辑。
     *
     * <p>优先通过 Redis 的 {@code HINCRBY} 原子增加，并标记脏数据集合；若 Redis 异常则平滑降级至本地 ConcurrentHashMap。</p>
     *
     * @param vid 视频公开业务短码
     * @param field 计数 Hash 域字段名 (view/like/star/share)
     * @param delta 变动量
     */
    private void adjustField(String vid, String field, long delta) {
        // 步骤 1: 参数合法性校验
        if (vid == null || vid.isBlank()) {
            return;
        }

        // 步骤 2: Redis 优先执行原子增量
        if (redisTemplate != null) {
            try {
                String key = COUNTER_KEY_PREFIX + vid;
                Long val = redisTemplate.opsForHash().increment(key, field, delta);
                // 约束控制：点赞与收藏等计数不可跌至负数，若被扣减至负则归零兜底
                if (val < 0) {
                    redisTemplate.opsForHash().put(key, field, "0");
                }
                // 续期当前计数 Hash 键
                renewTtl(key);
                // 标记该视频发生变动，加入脏集合等待异步调度刷盘
                redisTemplate.opsForSet().add(DIRTY_SET_KEY, vid);
                return;
            } catch (Exception e) {
                log.warn("写入 Redis 计数缓存异常，降级使用内存缓冲: {}", e.getMessage());
            }
        }

        // 步骤 3: Redis 异常或未配置时的本地内存降级分支
        localFallbackCache.compute(vid, (k, existing) -> {
            VideoCounter counter = existing != null ? existing : VideoCounter.createDefault(vid);
            switch (field) {
                case FIELD_VIEW -> counter.incrementViewCount(delta);
                case FIELD_LIKE -> counter.adjustLikeCount(delta);
                case FIELD_STAR -> counter.adjustStarCount(delta);
                case FIELD_SHARE -> counter.incrementShareCount(delta);
            }
            return counter;
        });
        localDirtySet.add(vid);
    }

    /**
     * 仅从当前缓存层（Redis 或本地降级缓存）尝试读取数据，不触发数据库冷加载。
     *
     * @param vid 视频短码
     * @return 缓存中的 VideoCounter 聚合根，未命中时返回 null
     */
    private VideoCounter getFromCacheOnly(String vid) {
        // 步骤 1: 优先尝试从 Redis 中查询
        if (redisTemplate != null) {
            try {
                String key = COUNTER_KEY_PREFIX + vid;
                Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
                if (entries != null && !entries.isEmpty()) {
                    // 步骤 1.1: 批量命中触发滑动续期
                    renewTtl(key);
                    return parseFromHashEntries(vid, entries);
                }
            } catch (Exception ignored) {
            }
        }
        // 步骤 2: 降级尝试从本地内存缓存中获取
        return localFallbackCache.get(vid);
    }

    /**
     * 将领域实体计数值同步写入当前缓存层（Redis Hash 或本地内存降级容器）。
     *
     * @param counter 互动计数领域实体
     */
    private void writeToCache(VideoCounter counter) {
        if (counter == null || counter.getVid() == null) {
            return;
        }
        // 步骤 1: 优先写入 Redis Hash 并设置 TTL
        if (redisTemplate != null) {
            try {
                String key = COUNTER_KEY_PREFIX + counter.getVid();
                Map<String, String> map = new HashMap<>();
                map.put(FIELD_VIEW, String.valueOf(counter.getViewCount()));
                map.put(FIELD_LIKE, String.valueOf(counter.getLikeCount()));
                map.put(FIELD_STAR, String.valueOf(counter.getStarCount()));
                map.put(FIELD_SHARE, String.valueOf(counter.getShareCount()));
                redisTemplate.opsForHash().putAll(key, map);
                renewTtl(key);
                return;
            } catch (Exception ignored) {
            }
        }
        // 步骤 2: 降级写入本地内存缓存
        localFallbackCache.put(counter.getVid(), counter);
    }

    /**
     * 将 Redis Hash 读取出的键值字典反序列化解析为 VideoCounter 领域对象。
     *
     * @param vid 视频公开业务短码
     * @param entries Redis Hash 域值字典
     * @return 填充计数值的领域聚合根对象
     */
    private VideoCounter parseFromHashEntries(String vid, Map<Object, Object> entries) {
        long view = parseLong(entries.get(FIELD_VIEW));
        long like = parseLong(entries.get(FIELD_LIKE));
        long star = parseLong(entries.get(FIELD_STAR));
        long share = parseLong(entries.get(FIELD_SHARE));

        return VideoCounter.builder()
                .vid(vid)
                .viewCount(Math.max(0L, view))
                .likeCount(Math.max(0L, like))
                .starCount(Math.max(0L, star))
                .shareCount(Math.max(0L, share))
                .commentCount(0L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 安全将对象解析为 long 数值，遇到 null 或解析异常时返回默认值 0L。
     *
     * @param obj 待解析的 Object 实例
     * @return 转换后的 long 数值，解析失败时返回 0L
     */
    private long parseLong(Object obj) {
        if (obj == null) {
            return 0L;
        }
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
