package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.star.StarFolder;
import java.util.List;
import java.util.Optional;

/**
 * 用户收藏夹仓储接口。
 */
public interface StarFolderRepository {

    /**
     * 根据主键 ID 查询收藏夹。
     *
     * @param id 收藏夹 ID
     * @return 收藏夹实体
     */
    Optional<StarFolder> findById(String id);

    /**
     * 查询用户的系统默认收藏夹。
     *
     * @param userId 用户 ID
     * @return 默认收藏夹实体 (若存在)
     */
    Optional<StarFolder> findDefaultByUserId(String userId);

    /**
     * 采用当前读（Locking Read FOR UPDATE）查询用户的系统默认收藏夹。
     *
     * <p>穿透 MVCC 快照限制，保证读取到其他并发事务已提交的最新默认收藏夹记录。</p>
     *
     * @param userId 用户 ID
     * @return 默认收藏夹实体 (若存在)
     */
    Optional<StarFolder> findDefaultByUserIdForUpdate(String userId);

    /**
     * 查询用户所有正常可用收藏夹列表。
     *
     * @param userId 用户 ID
     * @return 收藏夹实体列表
     */
    List<StarFolder> findActiveByUserId(String userId);

    /**
     * 保存新收藏夹。
     *
     * @param folder 实体对象
     */
    void save(StarFolder folder);

    /**
     * 更新收藏夹。
     *
     * @param folder 实体对象
     */
    void update(StarFolder folder);

    /**
     * 判断用户是否已存在同名可用收藏夹（未删除）。
     *
     * @param userId 用户 ID
     * @param title 收藏夹标题
     * @return true 若已存在同名活跃收藏夹
     */
    boolean existsByUserIdAndTitle(String userId, String title);

    /**
     * 判断用户是否存在同名可用收藏夹（排除指定收藏夹 ID）。
     *
     * @param userId 用户 ID
     * @param title 收藏夹标题
     * @param excludeFolderId 需排除的收藏夹 ID
     * @return true 若已存在同名活跃收藏夹
     */
    boolean existsByUserIdAndTitleExcludingId(String userId, String title, String excludeFolderId);

    /**
     * 根据主键逻辑删除收藏夹。
     *
     * @param id 收藏夹 ID
     */
    void deleteById(String id);
}
