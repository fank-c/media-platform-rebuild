package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.watch.WatchSession;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 观看会话仓储接口。
 */
public interface WatchSessionRepository {

    /**
     * 按会话 ID 读取会话。
     *
     * @param sessionId 会话主键
     * @return 观看会话；不存在时返回空
     */
    Optional<WatchSession> findById(String sessionId);

    /**
     * 按起播请求幂等键查询指定用户和视频的历史会话。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @param startRequestKey 起播幂等键
     * @return 观看会话实体；不存在时返回空
     */
    Optional<WatchSession> findByStartRequestKey(String userId, String vid, String startRequestKey);

    /**
     * 写入新的观看会话。
     *
     * @param session 观看会话实体
     */
    void insert(WatchSession session);

    /**
     * 更新会话累计时长、序号、断点与资格状态。
     *
     * @param session 观看会话实体
     */
    void updateHeartbeat(WatchSession session);

    /**
     * 关闭会话并写入关闭时间。
     *
     * @param sessionId 会话主键
     * @param now 关闭时间
     * @return 影响行数
     */
    int close(String sessionId, LocalDateTime now);

    /**
     * 物理删除保留期外的历史会话。
     *
     * @param threshold 保留期截止时间点
     * @param limit 单次最多删除行数
     * @return 影响行数
     */
    int deleteStaleBefore(LocalDateTime threshold, int limit);
}
