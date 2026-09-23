package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.star.StarItem;
import java.util.List;
import java.util.Optional;

/**
 * 收藏夹明细条目仓储接口。
 */
public interface StarItemRepository {

    /**
     * 根据收藏夹 ID 与视频编码查询单条收藏明细。
     *
     * @param folderId 收藏夹 ID
     * @param vid 视频编码
     * @return 收藏明细项
     */
    Optional<StarItem> findByFolderAndVid(String folderId, String vid);

    /**
     * 判断指定用户是否已收藏该视频（任一收藏夹包含均算已收藏）。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return true 若用户收藏了该视频
     */
    boolean isStarredByUser(String userId, String vid);

    /**
     * 分页查询指定收藏夹内的视频明细。
     *
     * @param folderId 收藏夹 ID
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 明细列表
     */
    List<StarItem> findByFolderId(String folderId, int offset, int limit);

    /**
     * 统计指定收藏夹内的条目总数。
     *
     * @param folderId 收藏夹 ID
     * @return 条目数
     */
    long countByFolderId(String folderId);

    /**
     * 保存单条收藏明细。
     *
     * @param item 明细实体
     */
    void save(StarItem item);

    /**
     * 从收藏夹移除单条视频明细。
     *
     * @param folderId 收藏夹 ID
     * @param vid 视频编码
     * @return 实际删除行数
     */
    int deleteByFolderAndVid(String folderId, String vid);

    /**
     * 从所有收藏夹移除指定用户针对某视频的收藏（全局取消收藏）。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return 实际删除行数
     */
    int deleteByUserAndVid(String userId, String vid);
}
