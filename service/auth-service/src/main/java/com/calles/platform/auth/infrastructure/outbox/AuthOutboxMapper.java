package com.calles.platform.auth.infrastructure.outbox;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * auth_outbox 的专用 SQL Mapper。
 *
 * <p>本接口只执行数据库读写和结果映射；租约、领取令牌、重试退避及发送编排由
 * {@link AuthOutboxRepository} 负责。所有发送入口都必须使用同一条条件领取语句。</p>
 */
@Mapper
public interface AuthOutboxMapper {

    /**
     * 在调用方事务中写入待发布事件。
     *
     * @param record 事件的业务字段和 JSON 信封
     * @param occurredAt 事件发生时间
     * @param pendingStatus 初始待发布状态
     * @param nextAttemptAt 首次可发送时间
     * @return 写入行数
     */
    @Insert("""
            INSERT INTO auth_outbox(event_id, aggregate_id, event_type, event_version, payload,
                trace_id, occurred_at, status, attempts, next_attempt_at)
            VALUES (#{record.eventId}, #{record.aggregateId}, #{record.eventType}, #{record.eventVersion},
                CAST(#{record.payload} AS JSON), #{record.traceId}, #{occurredAt},
                #{pendingStatus}, 0, #{nextAttemptAt})
            """)
    int insert(@Param("record") AuthOutboxRecord record, @Param("occurredAt") Timestamp occurredAt,
            @Param("pendingStatus") String pendingStatus, @Param("nextAttemptAt") Timestamp nextAttemptAt);

    /**
     * 只读发现仍可尝试发送的候选 ID；发现阶段不写租约，实际竞争由后续条件 UPDATE 决定。
     *
     * @param pendingStatus 待发送或重试状态
     * @param pendingBefore 不晚于该时间的待发送记录可被领取
     * @param processingStatus 已领取状态
     * @param leaseExpiredBefore 早于该时间的租约可被重新领取
     * @param maxAttempts 自动领取次数上限
     * @param limit 单轮候选上限
     * @return 按发生时间稳定排序的候选 ID
     */
    @Select("""
            SELECT event_id FROM auth_outbox
            WHERE attempts < #{maxAttempts}
              AND ((status = #{pendingStatus} AND next_attempt_at <= #{pendingBefore})
                OR (status = #{processingStatus} AND lease_until < #{leaseExpiredBefore}))
            ORDER BY occurred_at, event_id LIMIT #{limit}
            """)
    List<String> findClaimableEventIds(@Param("pendingStatus") String pendingStatus,
            @Param("pendingBefore") Timestamp pendingBefore,
            @Param("processingStatus") String processingStatus,
            @Param("leaseExpiredBefore") Timestamp leaseExpiredBefore,
            @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    /**
     * 查找已耗尽领取次数且租约到期的记录，供有界终态收敛使用。
     *
     * @param pendingStatus 待发送状态
     * @param pendingBefore PENDING 到期边界
     * @param processingStatus 已领取状态
     * @param leaseExpiredBefore PROCESSING 租约到期边界
     * @param maxAttempts 自动领取次数上限
     * @param limit 单轮收敛上限
     * @return 可以尝试收敛为 FAILED 的事件 ID
     */
    @Select("""
            SELECT event_id FROM auth_outbox
            WHERE attempts >= #{maxAttempts}
              AND ((status = #{pendingStatus} AND next_attempt_at <= #{pendingBefore})
                OR (status = #{processingStatus} AND lease_until < #{leaseExpiredBefore}))
            ORDER BY occurred_at, event_id LIMIT #{limit}
            """)
    List<String> findExhaustedEventIds(@Param("pendingStatus") String pendingStatus,
            @Param("pendingBefore") Timestamp pendingBefore,
            @Param("processingStatus") String processingStatus,
            @Param("leaseExpiredBefore") Timestamp leaseExpiredBefore,
            @Param("maxAttempts") int maxAttempts, @Param("limit") int limit);

    /**
     * 以完整资格谓词原子领取单条记录；只有返回 1 才允许读取发送快照。
     *
     * @param eventId 事件 ID
     * @param processingStatus 领取后的状态
     * @param owner 当前发布实例标识
     * @param leaseUntil 当前领取租约截止时间
     * @param claimToken 本次唯一领取令牌
     * @param maxAttempts 自动领取次数上限
     * @param pendingStatus 待发送状态
     * @param pendingBefore PENDING 到期边界
     * @param previousProcessingStatus 旧的已领取状态
     * @param leaseExpiredBefore PROCESSING 租约到期边界
     * @return 更新行数；0 表示正常竞争失败、未到期、终态或不存在
     */
    @Update("""
            UPDATE auth_outbox
            SET status=#{processingStatus}, lease_owner=#{owner}, lease_until=#{leaseUntil},
                claim_token=#{claimToken}, attempts=attempts+1
            WHERE event_id=#{eventId}
              AND attempts < #{maxAttempts}
              AND ((status = #{pendingStatus} AND next_attempt_at <= #{pendingBefore})
                OR (status = #{previousProcessingStatus} AND lease_until < #{leaseExpiredBefore}))
            """)
    int markClaimedIfEligible(@Param("eventId") String eventId,
            @Param("processingStatus") String processingStatus, @Param("owner") String owner,
            @Param("leaseUntil") Timestamp leaseUntil, @Param("claimToken") String claimToken,
            @Param("maxAttempts") int maxAttempts, @Param("pendingStatus") String pendingStatus,
            @Param("pendingBefore") Timestamp pendingBefore,
            @Param("previousProcessingStatus") String previousProcessingStatus,
            @Param("leaseExpiredBefore") Timestamp leaseExpiredBefore);

    /**
     * 读取当前领取令牌持有的发送快照，防止旧领取者读取到新持有者的内容。
     *
     * @param eventId 事件 ID
     * @param processingStatus 当前领取状态
     * @param claimToken 当前领取令牌
     * @return 发送所需的不可变快照；不匹配时返回 {@code null}
     */
    @Select("""
            SELECT event_id, payload, claim_token, attempts FROM auth_outbox
            WHERE event_id=#{eventId} AND status=#{processingStatus} AND claim_token=#{claimToken}
            """)
    @ConstructorArgs({
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "payload", javaType = String.class),
            @Arg(column = "claim_token", javaType = String.class),
            @Arg(column = "attempts", javaType = int.class)
    })
    ClaimedOutboxMessage findClaimedMessage(@Param("eventId") String eventId,
            @Param("processingStatus") String processingStatus, @Param("claimToken") String claimToken);

    /**
     * 将过期且次数耗尽的记录有界收敛为 FAILED；不能终结仍在有效租约内的发送。
     *
     * @param eventId 事件 ID
     * @param failedStatus 终态失败状态
     * @param errorCode 固定低敏感度错误分类
     * @param maxAttempts 自动领取次数上限
     * @param pendingStatus 待发送状态
     * @param pendingBefore PENDING 到期边界
     * @param processingStatus 已领取状态
     * @param leaseExpiredBefore PROCESSING 租约到期边界
     * @return 更新行数
     */
    @Update("""
            UPDATE auth_outbox
            SET status=#{failedStatus}, lease_owner=NULL, lease_until=NULL, claim_token=NULL,
                last_error_code=#{errorCode}
            WHERE event_id=#{eventId}
              AND attempts >= #{maxAttempts}
              AND ((status = #{pendingStatus} AND next_attempt_at <= #{pendingBefore})
                OR (status = #{processingStatus} AND lease_until < #{leaseExpiredBefore}))
            """)
    int markExhaustedIfEligible(@Param("eventId") String eventId, @Param("failedStatus") String failedStatus,
            @Param("errorCode") String errorCode, @Param("maxAttempts") int maxAttempts,
            @Param("pendingStatus") String pendingStatus, @Param("pendingBefore") Timestamp pendingBefore,
            @Param("processingStatus") String processingStatus,
            @Param("leaseExpiredBefore") Timestamp leaseExpiredBefore);

    /**
     * 以状态和领取标记为条件确认一次成功发布，旧领取者不得覆盖新状态。
     *
     * @param eventId 事件 ID
     * @param publishedStatus 成功发布状态
     * @param publishedAt Broker 确认成功的时间
     * @param processingStatus 当前领取状态
     * @param claimToken 当前领取标记
     * @return 更新行数
     */
    @Update("""
            UPDATE auth_outbox SET status=#{publishedStatus}, published_at=#{publishedAt}, lease_owner=NULL,
                lease_until=NULL, claim_token=NULL, last_error_code=NULL
            WHERE event_id=#{eventId} AND status=#{processingStatus} AND claim_token=#{claimToken}
            """)
    int markPublished(@Param("eventId") String eventId, @Param("publishedStatus") String publishedStatus,
            @Param("publishedAt") Timestamp publishedAt, @Param("processingStatus") String processingStatus,
            @Param("claimToken") String claimToken);

    /**
     * 以状态和领取标记为条件登记失败结果，并释放当前租约。
     *
     * @param eventId 事件 ID
     * @param targetStatus 继续重试或达到上限后的目标状态
     * @param nextAttemptAt 下一次尝试时间
     * @param errorCode 低敏感度错误分类
     * @param processingStatus 当前领取状态
     * @param claimToken 当前领取标记
     * @return 更新行数
     */
    @Update("""
            UPDATE auth_outbox SET status=#{targetStatus}, next_attempt_at=#{nextAttemptAt}, lease_owner=NULL,
                lease_until=NULL, claim_token=NULL, last_error_code=#{errorCode}
            WHERE event_id=#{eventId} AND status=#{processingStatus} AND claim_token=#{claimToken}
            """)
    int markFailed(@Param("eventId") String eventId, @Param("targetStatus") String targetStatus,
            @Param("nextAttemptAt") Timestamp nextAttemptAt, @Param("errorCode") String errorCode,
            @Param("processingStatus") String processingStatus, @Param("claimToken") String claimToken);

    /**
     * 读取不包含业务标识的 Outbox 积压聚合值。
     *
     * @param pendingStatus 待发送状态
     * @param failedStatus 达到重试上限的失败状态
     * @param processingStatus 已领取且尚未完成的状态
     * @return 当前积压监控快照
     */
    @Select("""
            SELECT COALESCE(SUM(status=#{pendingStatus}),0) AS pending_count,
                   COALESCE(SUM(status=#{failedStatus}),0) AS failed_count,
                   COALESCE(TIMESTAMPDIFF(SECOND,
                       MIN(CASE WHEN status IN (#{pendingStatus}, #{processingStatus}) THEN occurred_at END),
                       CURRENT_TIMESTAMP(3)),0) AS oldest_age_seconds
            FROM auth_outbox
            """)
    @ConstructorArgs({
            @Arg(column = "pending_count", javaType = long.class),
            @Arg(column = "failed_count", javaType = long.class),
            @Arg(column = "oldest_age_seconds", javaType = long.class)
    })
    OutboxBacklogSnapshot loadBacklogSnapshot(@Param("pendingStatus") String pendingStatus,
            @Param("failedStatus") String failedStatus, @Param("processingStatus") String processingStatus);
}
