package com.calles.platform.recommend.domain.repository;

import com.calles.platform.recommend.domain.model.event.EventConsumedRecord;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 互动事件消费幂等仓储接口 (EventConsumedRecordRepository)。
 *
 * <p>定义防重记录的持久化存取抽象，解耦底层存储细节。</p>
 */
public interface EventConsumedRecordRepository {

    /**
     * 尝试原子持久化消费记录；若该事件 ID 已存在则忽略并返回 false。
     *
     * @param record 待持久化的消费记录
     * @return true 表示首次消费并成功落库；false 表示已存在该记录 (重复投递拦截)
     */
    boolean saveIfAbsent(EventConsumedRecord record);

    /**
     * 根据事件全局唯一标识检索消费记录。
     *
     * @param eventId 事件唯一 ID
     * @return 消费记录可选容器
     */
    Optional<EventConsumedRecord> findByEventId(String eventId);

    /**
     * 批量清理指定截止时间以前的过期消费记录。
     *
     * @param before 截止时间戳
     * @return 删除的行数
     */
    int deleteBeforeConsumedAt(LocalDateTime before);
}
