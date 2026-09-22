package com.calles.platform.interaction.infrastructure.scheduling;

import com.calles.platform.interaction.domain.model.counter.VideoCounter;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoCounterPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.VideoCounterMapper;
import com.calles.platform.interaction.infrastructure.redis.VideoCounterRedisCache;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 视频互动统计快照异步刷盘调度器 (Write-Behind Flush)。
 *
 * <p>以可控频率（默认 5 秒）提取发生过增量变更的脏视频集合，将当前内存绝对值快照批量持久化至 MySQL，消除高频并发写库行锁。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoCounterFlushScheduler {

    /** 单次批处理从脏集合中提取的视频最大数量，防止过大事务或长时间占用数据库连接。 */
    private static final int BATCH_SIZE = 100;

    /** 视频互动计数缓存层与脏数据队列维护组件。 */
    private final VideoCounterRedisCache redisCache;

    /** 视频互动计数持久层数据访问接口。 */
    private final VideoCounterMapper counterMapper;

    /**
     * 周期性执行脏数据提取与批量持久化刷盘。
     *
     * <p>执行频率由配置项 {@code interaction.counter.flush-rate-ms} 决定，默认 5 秒；
     * 异常隔离：单个视频刷盘失败仅记录 error 日志，不阻断批处理其余数据与后续调度轮次。</p>
     */
    @Scheduled(fixedDelayString = "${interaction.counter.flush-rate-ms:5000}")
    public void flushDirtyCounters() {
        // 步骤 1: 从脏集合中弹出一批发生过计数值变动的视频短码
        Set<String> dirtyVids = redisCache.popDirtyVids(BATCH_SIZE);
        if (dirtyVids.isEmpty()) {
            return;
        }

        int successCount = 0;
        // 步骤 2: 遍历脏清单，提取最新绝对值快照并覆盖写入数据库
        for (String vid : dirtyVids) {
            try {
                VideoCounter snapshot = redisCache.getSnapshotForFlush(vid);
                if (snapshot != null) {
                    counterMapper.upsertSnapshot(VideoCounterPO.fromDomain(snapshot));
                    successCount++;
                }
            } catch (Exception e) {
                log.error("视频 [{}] 互动计数快照刷盘失败，等待下次变动自愈: {}", vid, e.getMessage());
            }
        }

        if (successCount > 0) {
            log.debug("完成互动计数快照批量刷盘，成功同步 [{}/{}] 条视频", successCount, dirtyVids.size());
        }
    }
}
