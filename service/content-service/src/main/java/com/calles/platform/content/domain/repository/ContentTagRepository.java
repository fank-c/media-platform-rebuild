package com.calles.platform.content.domain.repository;

import com.calles.platform.content.domain.model.tag.ContentTag;
import java.util.List;
import java.util.Optional;

/**
 * 标签全局仓储端口接口。
 */
public interface ContentTagRepository {

    /**
     * 按标签名称精确查找。
     *
     * @param name 标签名
     * @return 标签实体（若存在）
     */
    Optional<ContentTag> findByName(String name);

    /**
     * 按 ID 查找。
     *
     * @param id 标签 ID (UUID)
     * @return 标签实体（若存在）
     */
    Optional<ContentTag> findById(String id);

    /**
     * 批量按 ID 查找标签。
     *
     * @param ids 标签 ID (UUID) 列表
     * @return 对应的标签实体列表
     */
    List<ContentTag> findByIds(List<String> ids);

    /**
     * 保存新标签记录。
     *
     * @param tag 待保存标签
     * @return 影响行数
     */
    int insert(ContentTag tag);

    /**
     * 存在即返回，不存在则原子幂等创建并返回对应实体。
     *
     * @param name 标签名
     * @return 数据库中的持久化标签实体
     */
    ContentTag findOrCreate(String name);

    /**
     * 原子自增/自减引用热度计数。
     *
     * @param tagId 标签 ID (UUID)
     * @param delta 变更量（正数为增，负数为减）
     * @return 影响行数
     */
    int updateReferenceCount(String tagId, long delta);

    /**
     * 获取全站热门标签列表。
     *
     * @param limit 获取数量上限
     * @return 热门标签列表
     */
    List<ContentTag> findTopHotTags(int limit);
}
