package com.calles.platform.content.infrastructure.outbox;

import java.sql.Timestamp;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * content_outbox 事务性事件持久化 Mapper。
 */
@Mapper
public interface ContentOutboxMapper {

    /**
     * 在业务本地事务中原子写入待发布事件。
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
     * 发现待投递的 Outbox 事件 ID 列表。
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
     * 标记事件已成功发布到 MQ。
     */
    @Update("""
            UPDATE content_outbox
            SET status=#{publishedStatus}, published_at=#{publishedAt}, lease_owner=NULL,
                lease_until=NULL, claim_token=NULL, last_error_code=NULL
            WHERE event_id=#{eventId}
            """)
    int markPublished(@Param("eventId") String eventId, @Param("publishedStatus") String publishedStatus,
                      @Param("publishedAt") Timestamp publishedAt);
}
