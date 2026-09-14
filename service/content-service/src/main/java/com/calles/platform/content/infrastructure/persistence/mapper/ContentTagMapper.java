package com.calles.platform.content.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.content.infrastructure.persistence.entity.ContentTagPO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 标签全局字典 MyBatis-Plus 数据访问 Mapper。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：持久化访问 {@code content_tag} 表；</li>
 *   <li><b>防并发能力</b>：利用 {@code INSERT IGNORE} 实现全局标签词条的幂等安全写入；利用 {@code GREATEST(0, reference_count + delta)} 保证热度计数永不溢出为负数。</li>
 * </ul>
 * </p>
 */
@Mapper
public interface ContentTagMapper extends BaseMapper<ContentTagPO> {

    /**
     * 按标签唯一名称精确查询单条标签记录。
     *
     * @param name 标签文本名称
     * @return 匹配的标签持久化实体，未匹配时返回 null
     */
    @Select("SELECT * FROM content_tag WHERE name = #{name} LIMIT 1")
    ContentTagPO selectByName(@Param("name") String name);

    /**
     * 幂等插入新标签词条；若遇到唯一键冲突 (uk_tag_name) 则静默忽略。
     *
     * @param id 标签主键 ID (UUID)
     * @param name 标签名称
     * @return 影响行数（1=成功插入，0=冲突忽略）
     */
    @Insert("INSERT IGNORE INTO content_tag(id, name, reference_count, status) VALUES(#{id}, #{name}, 0, 'ACTIVE')")
    int insertIgnore(@Param("id") String id, @Param("name") String name);

    /**
     * 原子自增或自减标签的引用热度计数（保底非负数）。
     *
     * @param tagId 目标标签主键 ID
     * @param delta 变更幅度（正数递增，负数递减）
     * @return 影响行数
     */
    @Update("""
            UPDATE content_tag
            SET reference_count = GREATEST(0, reference_count + #{delta}),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{tagId}
            """)
    int updateReferenceCount(@Param("tagId") String tagId, @Param("delta") long delta);

    /**
     * 获取处于正常启用状态 (ACTIVE) 的热门高频标签列表。
     *
     * @param limit 获取数量上限
     * @return 按引用热度倒序排列的标签列表
     */
    @Select("SELECT * FROM content_tag WHERE status = 'ACTIVE' ORDER BY reference_count DESC LIMIT #{limit}")
    List<ContentTagPO> selectTopHot(@Param("limit") int limit);
}
