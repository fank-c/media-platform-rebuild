package com.calles.platform.recommend.domain.repository;

import com.calles.platform.recommend.domain.model.feedback.FeedbackLog;

import java.util.List;

/**
 * 行为反馈流水仓储接口。
 *
 * <p>提供不可篡改行为事实的单笔/批量追加与用户时序流水查询契约。</p>
 */
public interface FeedbackLogRepository {

    /**
     * 单笔持久化行为流水记录。
     *
     * @param log 行为流水实体
     */
    void save(FeedbackLog log);

    /**
     * 批量持久化行为流水记录。
     *
     * @param logs 行为流水实体列表
     */
    void saveBatch(List<FeedbackLog> logs);

    /**
     * 查询指定用户最近的行为流水记录 (时间倒序)。
     *
     * @param userId 用户账号ID
     * @param limit 最大返回条数
     * @return 行为流水实体列表
     */
    List<FeedbackLog> findRecentByUserId(String userId, int limit);
}
