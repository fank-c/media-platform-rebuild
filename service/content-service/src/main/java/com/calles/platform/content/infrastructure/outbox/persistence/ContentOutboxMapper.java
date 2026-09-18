package com.calles.platform.content.infrastructure.outbox.persistence;

import com.calles.platform.content.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.content.infrastructure.outbox.model.ContentOutboxRecord;
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
 * 事务性发件箱 (Transactional Outbox) 数据持久化 Mapper。
 *
 * <p>职责与机制说明：
 * <ul>
 *   <li><b>所属边界</b>：发件箱数据持久层，只执行数据库读写与结果映射；</li>
 *   <li><b>协作对象</b>：配合 {@link ContentOutboxRepository} 执行原子 CAS 认领、租约维护与结果回写；</li>
 *   <li><b>状态机流转</b>：PENDING（待发布） -&gt; PROCESSING（处理中/加锁中） -&gt; PUBLISHED（已成功投递）或 FAILED（重试超限失败）。</li>
 * </ul>
 * </p>
 */
@Mapper
public interface ContentOutboxMapper {

    /**
     * 在业务本地数据库事务中原子写入一条待投递的领域事件记录。
     *
     * @param record 事件不可变传输对象 (包含 eventId, aggregateId, payload 等)
     * @param occurredAt 业务事实发生的时间戳
     * @param pendingStatus 初始待处理状态字面量（通常为 "PENDING"）
     * @param nextAttemptAt 首次允许拉取尝试的时间戳
     * @return 插入成功的记录行数
     */
    @Insert("""
            INSERT INTO content_outbox(event_id, aggregate_id, event_type, event_version, payload,
                trace_id, occurred_at, status, attempts, next_attempt_at)
            VALUES (#{record.eventId}, #{record.aggregateId}, #{record.eventType}, #{record.eventVersion},
                CAST(#{record.payload} AS JSON), #{record.traceId}, #{occurredAt},
                #{pendingStatus}, 0, #{nextAttemptAt})
            """)
    int insert(@Param("record") ContentOutboxRecord record, @Param("occurredAt") Timestamp occurredAt,
               @Param("pendingStatus") String pendingStatus, @Param("nextAttemptAt") Timestamp nextAttemptAt);

    /**
     * 轮询发现当前可供锁定的待投递 Outbox 事件 ID 列表（包含重试到期及租约超时的事件）。
     *
     * @param pendingStatus 待处理状态（如 "PENDING"）
     * @param pendingBefore 待处理截止时间（当前时间戳）
     * @param processingStatus 处理中状态（如 "PROCESSING"）
     * @param leaseExpiredBefore 租约过期判定时间戳
     * @param maxAttempts 最大允许重试次数上限
     * @param limit 本次拉取的最大记录条数
     * @return 可认领的事件全局唯一 ID (UUID) 列表
     */
    @Select("""
            SELECT event_id FROM content_outbox
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
     * 查找已耗尽投递次数且租约已到期的记录，供有界终态收敛至 FAILED。
     *
     * @param pendingStatus 待发送状态
     * @param pendingBefore 待发送截止时间
     * @param processingStatus 已认领状态
     * @param leaseExpiredBefore 租约到期截止时间
     * @param maxAttempts 自动投递次数上限
     * @param limit 单轮拉取上限
     * @return 可收敛为 FAILED 的事件 ID 列表
     */
    @Select("""
            SELECT event_id FROM content_outbox
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
     * 以完整资格谓词原子认领单条发件箱记录；通过 CAS 更新保证多实例并发互斥。
     *
     * @param eventId 待认领的事件 ID
     * @param processingStatus 认领后流转的目标状态（通常为 PROCESSING）
     * @param owner 当前发布实例节点标识
     * @param leaseUntil 本次认领租约到期时间戳
     * @param claimToken 本次认领生成的唯一租约令牌 (UUID)
     * @param maxAttempts 最大允许尝试次数
     * @param pendingStatus 待处理状态（PENDING）
     * @param pendingBefore 允许重试的截止时间
     * @param previousProcessingStatus 前置处理中状态（PROCESSING）
     * @param leaseExpiredBefore 允许重新抢占的租约过期时间
     * @return 更新影响的记录行数；返回 1 表示抢占成功，返回 0 表示已被其他节点抢占或未就绪
     */
    @Update("""
            UPDATE content_outbox
            SET status=#{processingStatus}, lease_owner=#{owner}, lease_until=#{leaseUntil},
                claim_token=#{claimToken}, attempts=attempts+1
            WHERE event_id=#{eventId}
              AND attempts < #{maxAttempts}
              AND ((status = #{pendingStatus} AND next_attempt_at <= #{pendingBefore})
                OR (status = #{previousProcessingStatus} AND lease_until < #{leaseExpiredBefore}))
            """)
    int markClaimedIfEligible(@Param("eventId") String eventId,
                              @Param("processingStatus") String processingStatus,
                              @Param("owner") String owner,
                              @Param("leaseUntil") Timestamp leaseUntil,
                              @Param("claimToken") String claimToken,
                              @Param("maxAttempts") int maxAttempts,
                              @Param("pendingStatus") String pendingStatus,
                              @Param("pendingBefore") Timestamp pendingBefore,
                              @Param("previousProcessingStatus") String previousProcessingStatus,
                              @Param("leaseExpiredBefore") Timestamp leaseExpiredBefore);

    /**
     * 读取当前租约令牌持有的不可变发送快照，防范读取到已转移的并发快照。
     *
     * @param eventId 事件 ID
     * @param processingStatus 当前认领处理状态
     * @param claimToken 当前租约令牌
     * @return 映射出的不可变发送快照对象；不匹配返回 null
     */
    @Select("""
            SELECT event_id, event_type, payload, trace_id, claim_token, attempts FROM content_outbox
            WHERE event_id=#{eventId} AND status=#{processingStatus} AND claim_token=#{claimToken}
            """)
    @ConstructorArgs({
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "event_type", javaType = String.class),
            @Arg(column = "payload", javaType = String.class),
            @Arg(column = "trace_id", javaType = String.class),
            @Arg(column = "claim_token", javaType = String.class),
            @Arg(column = "attempts", javaType = int.class)
    })
    ClaimedOutboxMessage findClaimedMessage(@Param("eventId") String eventId,
                                           @Param("processingStatus") String processingStatus,
                                           @Param("claimToken") String claimToken);

    /**
     * 将投递次数已耗尽且租约已过期的记录原子收敛置位为 FAILED。
     *
     * @param eventId 事件 ID
     * @param failedStatus 终态失败状态（通常为 FAILED）
     * @param errorCode 错误原因归类代号
     * @param maxAttempts 自动投递次数上限
     * @param pendingStatus 待处理状态
     * @param pendingBefore 待处理截止时间
     * @param processingStatus 已认领状态
     * @param leaseExpiredBefore 租约过期判定时间
     * @return 影响行数
     */
    @Update("""
            UPDATE content_outbox
            SET status=#{failedStatus}, lease_owner=NULL, lease_until=NULL, claim_token=NULL,
                last_error_code=#{errorCode}
            WHERE event_id=#{eventId}
              AND attempts >= #{maxAttempts}
              AND ((status = #{pendingStatus} AND next_attempt_at <= #{pendingBefore})
                OR (status = #{processingStatus} AND lease_until < #{leaseExpiredBefore}))
            """)
    int markExhaustedIfEligible(@Param("eventId") String eventId,
                                @Param("failedStatus") String failedStatus,
                                @Param("errorCode") String errorCode,
                                @Param("maxAttempts") int maxAttempts,
                                @Param("pendingStatus") String pendingStatus,
                                @Param("pendingBefore") Timestamp pendingBefore,
                                @Param("processingStatus") String processingStatus,
                                @Param("leaseExpiredBefore") Timestamp leaseExpiredBefore);

    /**
     * 以状态与领取令牌为条件确认发布成功，清理租约并设置 PUBLISHED 终态。
     *
     * @param eventId 事件唯一全局 ID (UUID)
     * @param publishedStatus 终态成功标记（通常为 "PUBLISHED"）
     * @param publishedAt 确认发布完成的时间戳
     * @param processingStatus 当前认领状态（通常为 "PROCESSING"）
     * @param claimToken 本次认领的唯一租约令牌
     * @return 影响的数据库记录行数；若为 0 说明租约已被并发覆盖，不再覆盖
     */
    @Update("""
            UPDATE content_outbox
            SET status=#{publishedStatus}, published_at=#{publishedAt}, lease_owner=NULL,
                lease_until=NULL, claim_token=NULL, last_error_code=NULL
            WHERE event_id=#{eventId} AND status=#{processingStatus} AND claim_token=#{claimToken}
            """)
    int markPublished(@Param("eventId") String eventId,
                      @Param("publishedStatus") String publishedStatus,
                      @Param("publishedAt") Timestamp publishedAt,
                      @Param("processingStatus") String processingStatus,
                      @Param("claimToken") String claimToken);

    /**
     * 以状态与领取令牌为条件登记投递失败结果，释放租约并设置指数退避重试时间。
     *
     * @param eventId 事件唯一全局 ID
     * @param targetStatus 失败后的目标状态（PENDING 或 FAILED）
     * @param nextAttemptAt 下一次允许拉取重试的时间戳
     * @param errorCode 错误原因标识
     * @param processingStatus 当前处理中状态（PROCESSING）
     * @param claimToken 本次认领租约令牌
     * @return 影响行数
     */
    @Update("""
            UPDATE content_outbox
            SET status=#{targetStatus}, next_attempt_at=#{nextAttemptAt}, lease_owner=NULL,
                lease_until=NULL, claim_token=NULL, last_error_code=#{errorCode}
            WHERE event_id=#{eventId} AND status=#{processingStatus} AND claim_token=#{claimToken}
            """)
    int markFailed(@Param("eventId") String eventId,
                   @Param("targetStatus") String targetStatus,
                   @Param("nextAttemptAt") Timestamp nextAttemptAt,
                   @Param("errorCode") String errorCode,
                   @Param("processingStatus") String processingStatus,
                   @Param("claimToken") String claimToken);
}
