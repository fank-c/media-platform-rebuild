package com.calles.platform.auth.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 认证账号资料补齐的专用 SQL Mapper。
 *
 * <p>本接口只负责候选筛选和补齐进度登记；事件生成、事务边界和 Outbox 写入由应用服务协调。</p>
 */
@Mapper
public interface ProfileBackfillMapper {

    /**
     * 查询尚未生成 v1 账号创建事件的有效普通账号。
     *
     * @param batchSize 单次扫描上限
     * @return 按创建时间和账户 ID 稳定排序的最小候选快照
     */
    @Select("""
            SELECT a.id AS account_id, a.created_at
            FROM auth_account a
            LEFT JOIN auth_profile_backfill_progress p
              ON p.account_id=a.id AND p.event_type='auth.account.created' AND p.event_version=1
            WHERE a.role='USER' AND a.status='ACTIVE' AND a.deleted=0 AND p.account_id IS NULL
            ORDER BY a.created_at, a.id LIMIT #{batchSize}
            """)
    @ConstructorArgs({
            @Arg(column = "account_id", javaType = String.class),
            @Arg(column = "created_at", javaType = java.sql.Timestamp.class)
    })
    List<ProfileBackfillCandidate> findCandidates(@Param("batchSize") int batchSize);

    /**
     * 以唯一进度键抢占某账号的补齐资格；重复插入返回 0，调用方不得再写入 Outbox。
     *
     * @param accountId 认证账户 ID
     * @param eventId 本次新建的 Outbox 事件 ID
     * @return 1 表示取得补齐资格，0 表示已有进度或输掉并发竞争
     */
    @Insert("""
            INSERT IGNORE INTO auth_profile_backfill_progress(account_id, event_type, event_version, event_id)
            VALUES (#{accountId}, 'auth.account.created', 1, #{eventId})
            """)
    int tryClaimProgress(@Param("accountId") String accountId, @Param("eventId") String eventId);
}
