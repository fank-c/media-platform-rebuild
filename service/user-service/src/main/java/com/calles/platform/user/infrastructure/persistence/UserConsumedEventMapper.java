package com.calles.platform.user.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户事件幂等登记入口，与资料初始化共用本地事务。
 */
@Mapper
public interface UserConsumedEventMapper {

    /**
     * 尝试登记事件；返回 0 表示相同消费者已经处理过该 eventId。
     */
    @Insert("INSERT IGNORE INTO user_consumed_event(consumer_name,event_id,event_type,event_version,aggregate_id,outcome) "
            + "VALUES(#{consumerName},#{eventId},#{eventType},#{eventVersion},#{aggregateId},#{outcome})")
    int insertIfAbsent(@Param("consumerName") String consumerName, @Param("eventId") String eventId,
            @Param("eventType") String eventType, @Param("eventVersion") int eventVersion,
            @Param("aggregateId") String aggregateId, @Param("outcome") String outcome);

    /** 在同一事务完成资料初始化后记录最终处理结果。 */
    @Update("UPDATE user_consumed_event SET outcome=#{outcome},processed_at=CURRENT_TIMESTAMP(3) "
            + "WHERE consumer_name=#{consumerName} AND event_id=#{eventId}")
    int updateOutcome(@Param("consumerName") String consumerName, @Param("eventId") String eventId,
            @Param("outcome") String outcome);
}
