package com.calles.platform.content.domain.repository;

import com.calles.platform.content.domain.model.tag.ContentTag;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 标签全局仓储端口接口 (Domain Repository Interface)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：定义领域层针对标签字典与热度计数的持久化契约，与底层数据库技术解耦；</li>
 *   <li><b>协作对象</b>：由基础设施层 {@link com.calles.platform.content.infrastructure.persistence.repository.ContentTagRepositoryImpl} 实现；</li>
 *   <li><b>核心契约</b>：包含精确查找、批量拉取、原子按需创建 (findOrCreate)、原子热度增减以及热门榜单查询。</li>
 * </ul>
 * </p>
 */
public interface ContentTagRepository {

    /**
     * 按标签唯一名称精确检索。
     *
     * @param name 标签名（如 "Java"）
     * @return 包含领域实体的 {@link Optional}，未找到时返回 empty
     */
    Optional<ContentTag> findByName(String name);

    /**
     * 根据主键 ID 精确检索标签。
     *
     * @param id 标签全局主键 ID (UUID)
     * @return 包含领域实体的 {@link Optional}，未找到时返回 empty
     */
    Optional<ContentTag> findById(String id);

    /**
     * 批量按主键 ID 查找标签实体列表。
     *
     * @param ids 标签 ID (UUID) 列表
     * @return 对应的标签领域实体列表（若入参为空则返回空列表）
     */
    List<ContentTag> findByIds(List<String> ids);

    /**
     * 新增持久化单条标签记录。
     *
     * @param tag 待保存的标签聚合实体
     * @return 影响的数据库行数
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
     * 批量按标签名称集合检索已存在的标签实体列表。
     *
     * @param names 标签名集合
     * @return 匹配的标签领域实体列表（若入参为空则返回空列表）
     */
    List<ContentTag> findByNames(Collection<String> names);

    /**
     * 批量存在即返回，不存在则原子幂等创建并返回对应实体列表。
     *
     * @param names 标签名集合
     * @return 对应的持久化标签实体列表
     */
    List<ContentTag> findOrCreateBatch(Collection<String> names);

    /**
     * 原子自增或自减引用热度计数（底层保底不低于 0）。
     *
     * @param tagId 标签全局主键 ID (UUID)
     * @param delta 变更量（正数表示递增，负数表示递减）
     * @return 影响行数
     */
    int updateReferenceCount(String tagId, long delta);

    /**
     * 批量原子自增或自减标签的引用热度计数（底层保底非负数，内部升序排列防死锁）。
     *
     * @param tagIds 目标标签主键 ID (UUID) 集合
     * @param delta 变更量（正数表示递增，负数表示递减）
     * @return 影响行数
     */
    int batchUpdateReferenceCount(Collection<String> tagIds, long delta);

    /**
     * 获取全站高热度有效标签排行列表。
     *
     * @param limit 获取数量上限
     * @return 按引用热度倒序排列的活跃标签实体列表
     */
    List<ContentTag> findTopHotTags(int limit);
}
