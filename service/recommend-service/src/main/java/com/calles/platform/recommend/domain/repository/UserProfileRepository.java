package com.calles.platform.recommend.domain.repository;

import com.calles.platform.recommend.domain.model.profile.UserProfile;

import java.util.Optional;

/**
 * 用户画像聚合根仓储接口。
 *
 * <p>提供用户画像状态快照的持久化读写与乐观锁版本控制契约。</p>
 */
public interface UserProfileRepository {

    /**
     * 根据用户账号 ID 查询其画像快照。
     *
     * @param userId 用户全局唯一标识
     * @return 匹配的画像聚合根，不存在时返回 Optional.empty()
     */
    Optional<UserProfile> findByUserId(String userId);

    /**
     * 保存或更新用户画像快照。
     *
     * <p>实现层须处理版本号自增与并发乐观锁冲突。</p>
     *
     * @param profile 用户画像聚合根
     */
    void saveOrUpdate(UserProfile profile);
}
