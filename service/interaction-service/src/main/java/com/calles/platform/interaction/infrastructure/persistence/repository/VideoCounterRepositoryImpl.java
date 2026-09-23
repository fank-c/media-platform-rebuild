package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.domain.repository.VideoCounterRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoCounterPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoCounterMapper;
import com.calles.platform.interaction.infrastructure.redis.VideoCounterRedisCache;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频互动统计聚合根仓储实现类 (Cache-Aside + Write-Behind 架构)。
 *
 * <p>读取优先命中 Redis 内存缓存；高频增量变动全在 Redis 内存完成并由后台调度器平滑批量刷盘。</p>
 */
@Repository
@RequiredArgsConstructor
public class VideoCounterRepositoryImpl implements VideoCounterRepository {

    /** 视频互动统计计数持久层数据访问接口。 */
    private final VideoCounterMapper mapper;

    /** 视频互动计数 Redis 缓存与 Write-Behind 异步缓冲组件。 */
    private final VideoCounterRedisCache redisCache;

    /**
     * 根据视频短码查询互动统计聚合根。
     *
     * <p>优先走缓存读取；若缓存未命中则通过 lambda 回调从数据库冷加载并回填缓存，避免缓存击穿。</p>
     *
     * @param vid 视频公开业务短码
     * @return 包含计数信息的领域对象 Optional；入参为空或查无数据时返回相应空/默认实例
     */
    @Override
    public Optional<VideoCounter> findByVid(String vid) {
        // 步骤 1: 校验业务短码有效性
        if (vid == null || vid.isBlank()) {
            return Optional.empty();
        }
        // 步骤 2: 优先查缓存，未命中时执行 DB 回调加载并回填
        VideoCounter counter = redisCache.getCounter(vid, () -> {
            VideoCounterPO po = mapper.selectById(vid);
            return po != null ? po.toDomain() : VideoCounter.createDefault(vid);
        });
        return Optional.ofNullable(counter);
    }

    /**
     * 批量查询多个视频的互动统计聚合根。
     *
     * <p>支持缓存与冷加载混合查询：已在缓存中的直接返回，缓存缺失的批量回源数据库并回填。</p>
     *
     * @param vids 视频短码集合
     * @return 互动统计聚合根列表
     */
    @Override
    public List<VideoCounter> findByVids(Collection<String> vids) {
        // 步骤 1: 空入参防御
        if (vids == null || vids.isEmpty()) {
            return List.of();
        }
        // 步骤 2: 委托 RedisCache 执行批量查询与差集回源
        return redisCache.getBatchCounters(vids, missingVids -> {
            List<VideoCounterPO> pos = mapper.selectBatchIds(missingVids);
            if (pos == null) {
                return List.of();
            }
            return pos.stream().map(VideoCounterPO::toDomain).toList();
        });
    }

    /**
     * 保存或更新视频互动统计计数聚合根快照。
     *
     * @param counter 领域聚合根实体
     */
    @Override
    public void save(VideoCounter counter) {
        if (counter == null) {
            return;
        }
        // 步骤 1: 领域实体转换为持久化 PO
        VideoCounterPO po = VideoCounterPO.fromDomain(counter);
        // 步骤 2: 覆盖写入快照（利用 ON DUPLICATE KEY UPDATE 幂等保存）
        mapper.upsertSnapshot(po);
    }

    /**
     * 增加视频播放量。
     *
     * <p>直接在 Redis 内存中原子自增并标记脏数据，由后台调度器平滑异步刷盘。</p>
     *
     * @param vid 视频短码
     * @param delta 播放增量
     */
    @Override
    public void incrementViewCount(String vid, long delta) {
        redisCache.incrementView(vid, delta);
    }

    /**
     * 调整视频点赞计数。
     *
     * @param vid 视频短码
     * @param delta 点赞变动量 (+1 或 -1)
     */
    @Override
    public void adjustLikeCount(String vid, long delta) {
        redisCache.adjustLike(vid, delta);
    }

    /**
     * 调整视频收藏计数。
     *
     * @param vid 视频短码
     * @param delta 收藏变动量 (+1 或 -1)
     */
    @Override
    public void adjustStarCount(String vid, long delta) {
        redisCache.adjustStar(vid, delta);
    }

    /**
     * 增加视频分享计数。
     *
     * @param vid 视频短码
     * @param delta 分享增量
     */
    @Override
    public void incrementShareCount(String vid, long delta) {
        redisCache.incrementShare(vid, delta);
    }
}
