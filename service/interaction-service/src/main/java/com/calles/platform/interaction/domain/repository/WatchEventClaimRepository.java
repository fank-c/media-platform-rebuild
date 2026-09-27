package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.watch.WatchEventClaim;
import com.calles.platform.interaction.domain.model.watch.WatchEventType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Set;

/**
 * 观看事件凭据仓储接口。
 */
public interface WatchEventClaimRepository {

    /**
     * 尝试抢占一条事件凭据。
     *
     * <p>实现必须依赖数据库唯一键判定重复：插入冲突视为"本会话该事件已被抢占"，返回 false。</p>
     *
     * @param claim 待落库的事件凭据
     * @return true 表示本次抢占成功，调用方才允许写 Outbox、计数增量与冷却时间
     */
    boolean tryClaim(WatchEventClaim claim);

    /**
     * 查询指定会话已经抢占到的凭据类型集合。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param sessionId 会话 ID
     * @return 已抢占的凭据类型；无记录时返回空集合
     */
    Set<WatchEventType> findClaimedTypes(String userId, String vid, String sessionId);

    /**
     * 查询给定视频集合中曾经产生过完播凭据的视频短码。
     *
     * @param userId 用户 ID
     * @param vids 待核查视频短码集合，为空时直接返回空集合
     * @return 存在完播凭据的视频短码集合
     */
    Set<String> findCompletedVids(String userId, Collection<String> vids);

    /**
     * 回填同事务产生的 Outbox 事件 ID。
     *
     * @param id 凭据主键
     * @param outboxEventId Outbox 事件 ID
     */
    void attachOutboxEventId(String id, String outboxEventId);

    /**
     * 物理删除保留期外的事件凭据。
     *
     * @param threshold 保留期截止时间点
     * @param limit 单次最多删除行数
     * @return 影响行数
     */
    int deleteBefore(LocalDateTime threshold, int limit);
}
