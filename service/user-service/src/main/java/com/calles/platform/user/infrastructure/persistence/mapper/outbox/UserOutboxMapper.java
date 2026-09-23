package com.calles.platform.user.infrastructure.persistence.mapper.outbox;

import com.calles.platform.user.infrastructure.outbox.model.ClaimedOutboxMessage;
import com.calles.platform.user.infrastructure.outbox.model.UserOutboxRecord;
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
 * 用户服务事务性发件箱 (Transactional Outbox) 数据持久化 Mapper。
 */
@Mapper
public interface UserOutboxMapper {

    /**
     * 在业务本地数据库事务中原子写入一条待投递的领域事件记录。
     *
     * @param record 事件不可变传输对象
     * @param occurredAt 业务事实发生的时间戳
     * @param pendingStatus 初始待处理状态字面量（通常为 "PENDING"）
     * @param nextAttemptAt 首次允许拉取尝试的时间戳
     * @return 插入成功的记录行数
     */
    @Insert("""
            INSERT INTO user_outbox(event_id, aggregate_id, event_type, event_version, payload,
                trace_id, occurred_at, status, attempts, next_attempt_at)
            VALUES (#{record.eventId}, #{record.aggregateId}, #{record.eventType}, #{record.eventVersion},
                CAST(#{record.payload} AS JSON), #{record.traceId}, #{occurredAt},
                #{pendingStatus}, 0, #{nextAttemptAt})
            """)
    int insert(@Param("record") UserOutboxRecord record, @Param("occurredAt") Timestamp occurredAt,
               @Param("pendingStatus") String pendingStatus, @Param("nextAttemptAt") Timestamp nextAttemptAt);

    /**
     * 轮询发现当前可供锁定的待投递 Outbox 事件 ID 列表。
     */
    @Select("""
            SELECT event_id FROM user_outbox
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
     * 查找已耗尽投递次数且租约已到期的记录。
     */
    @Select("""
            SELECT event_id FROM user_outbox
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
     */
    @Update("""
            UPDATE user_outbox
            SET status = #{processingStatus},
                lease_owner = #{owner},
                lease_until = #{leaseUntil},
                claim_token = #{token},
                attempts = attempts + 1
            WHERE event_id = #{eventId}
              AND attempts < #{maxAttempts}
              AND ((status = #{pendingStatus} AND next_attempt_at <= #{pendingBefore})
                OR (status = #{currentProcessingStatus} AND lease_until < #{leaseExpiredBefore}))
            """)
    int markClaimedIfEligible(@Param("eventId") String eventId,
                              @Param("processingStatus") String processingStatus,
                              @Param("owner") String owner,
                              @Param("leaseUntil") Timestamp leaseUntil,
                              @Param("token") String token,
                              @Param("maxAttempts") int maxAttempts,
                              @Param("pendingStatus") String pendingStatus,
                              @Param("pendingBefore") Timestamp pendingBefore,
                              @Param("currentProcessingStatus") String currentProcessingStatus,
                              @Param("leaseExpiredBefore") Timestamp leaseExpiredBefore);

    /**
     * 查询已被当前令牌认领成功的不可变消息快照。
     */
    @Select("""
            SELECT event_id, aggregate_id, event_type, event_version,
                   CAST(payload AS CHAR) AS payload, trace_id, occurred_at, attempts, claim_token
            FROM user_outbox
            WHERE event_id = #{eventId}
              AND status = #{processingStatus}
              AND claim_token = #{claimToken}
            """)
    @ConstructorArgs({
            @Arg(column = "event_id", javaType = String.class),
            @Arg(column = "aggregate_id", javaType = String.class),
            @Arg(column = "event_type", javaType = String.class),
            @Arg(column = "event_version", javaType = int.class),
            @Arg(column = "payload", javaType = String.class),
            @Arg(column = "trace_id", javaType = String.class),
            @Arg(column = "occurred_at", javaType = java.time.Instant.class),
            @Arg(column = "attempts", javaType = int.class),
            @Arg(column = "claim_token", javaType = String.class)
    })
    ClaimedOutboxMessage findClaimedMessage(@Param("eventId") String eventId,
                                           @Param("processingStatus") String processingStatus,
                                           @Param("claimToken") String claimToken);

    /**
     * 将次数超限记录收敛为 FAILED。
     */
    @Update("""
            UPDATE user_outbox
            SET status = #{failedStatus},
                last_error_code = #{errorCode}
            WHERE event_id = #{eventId}
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
     * 仅由持有有效令牌的调用者标记发布成功。
     */
    @Update("""
            UPDATE user_outbox
            SET status = #{publishedStatus},
                published_at = #{publishedAt},
                lease_owner = NULL,
                lease_until = NULL,
                claim_token = NULL
            WHERE event_id = #{eventId}
              AND status = #{processingStatus}
              AND claim_token = #{claimToken}
            """)
    int markPublished(@Param("eventId") String eventId,
                      @Param("publishedStatus") String publishedStatus,
                      @Param("publishedAt") Timestamp publishedAt,
                      @Param("processingStatus") String processingStatus,
                      @Param("claimToken") String claimToken);

    /**
     * 投递失败时回写退避时间并释放租约。
     */
    @Update("""
            UPDATE user_outbox
            SET status = #{nextStatus},
                next_attempt_at = #{nextAttemptAt},
                last_error_code = #{errorCode},
                lease_owner = NULL,
                lease_until = NULL,
                claim_token = NULL
            WHERE event_id = #{eventId}
              AND status = #{processingStatus}
              AND claim_token = #{claimToken}
            """)
    int markFailed(@Param("eventId") String eventId,
                   @Param("nextStatus") String nextStatus,
                   @Param("nextAttemptAt") Timestamp nextAttemptAt,
                   @Param("errorCode") String errorCode,
                   @Param("processingStatus") String processingStatus,
                   @Param("claimToken") String claimToken);
}
