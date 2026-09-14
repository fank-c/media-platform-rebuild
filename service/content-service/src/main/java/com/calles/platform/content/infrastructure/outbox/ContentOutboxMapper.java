package com.calles.platform.content.infrastructure.outbox;

import java.sql.Timestamp;
import java.util.List;
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
 *   <li><b>所属边界</b>：用于在本地数据库事务中原子写入领域事件，杜绝业务数据与消息队列跨服务双写不一致；</li>
 *   <li><b>协作对象</b>：配合后台定时任务或轮询器扫描 {@code content_outbox} 表拉取待投递事件，并推送到 RabbitMQ 交换机；</li>
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
     * 标记指定事件已成功发布到 RabbitMQ 消息总线，清理租约并设置已发布时间戳。
     *
     * @param eventId 事件唯一全局 ID (UUID)
     * @param publishedStatus 终态成功标记（通常为 "PUBLISHED"）
     * @param publishedAt 确认发布完成的时间戳
     * @return 影响的数据库记录行数
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
