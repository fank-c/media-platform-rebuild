package com.calles.platform.interaction.domain.repository;

import com.calles.platform.interaction.domain.model.star.StarItem;
import java.util.List;
import java.util.Optional;

/**
 * 收藏夹明细条目仓储接口。
 */
public interface StarItemRepository {

    /**
     * 根据收藏夹 ID 与视频编码查询单条有效收藏明细（排除已逻辑删除条目）。
     *
     * @param folderId 收藏夹 ID
     * @param vid 视频编码
     * @return 收藏明细项
     */
    Optional<StarItem> findByFolderAndVid(String folderId, String vid);

    /**
     * 物理检索指定收藏夹中特定视频的条目（包含已逻辑删除记录，用于唯一键冲突检测与自愈）。
     *
     * @param folderId 收藏夹 ID
     * @param vid 视频编码
     * @return 收藏明细项 (包含 deleted 状态)
     */
    Optional<StarItem> findPhysicalByFolderAndVid(String folderId, String vid);

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
     * 自愈复活已逻辑删除的收藏明细条目。
     *
     * @param id 条目主键 UUID
     */
    void revive(String id);

    /**
     * 从收藏夹移除单条视频明细。
     *
     * @param folderId 收藏夹 ID
     * @param vid 视频编码
     * @return 实际删除行数
     */
    int deleteByFolderAndVid(String folderId, String vid);

    /**
     * 从指定收藏夹移除属于当前用户的视频明细（附带用户归属校验，防止越权）。
     *
     * @param folderId 收藏夹 ID
     * @param vid 视频编码
     * @param userId 操作用户账号 ID
     * @return 实际删除行数
     */
    int deleteByFolderAndVidAndUser(String folderId, String vid, String userId);

    /**
     * 从所有收藏夹移除指定用户针对某视频的收藏（全局取消收藏）。
     *
     * @param userId 用户 ID
     * @param vid 视频编码
     * @return 实际删除行数
     */
    int deleteByUserAndVid(String userId, String vid);
}
