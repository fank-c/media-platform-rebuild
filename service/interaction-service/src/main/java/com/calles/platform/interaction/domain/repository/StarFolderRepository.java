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
}
