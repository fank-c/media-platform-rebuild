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
 * 标签全局字典 MyBatis-Plus Mapper。
 */
@Mapper
public interface ContentTagMapper extends BaseMapper<ContentTagPO> {

    /**
     * 按名称查询标签。
     */
    @Select("SELECT * FROM content_tag WHERE name = #{name} LIMIT 1")
    ContentTagPO selectByName(@Param("name") String name);

    /**
     * 幂等插入新标签，名称冲突时忽略。
     */
    @Insert("INSERT IGNORE INTO content_tag(id, name, reference_count, status) VALUES(#{id}, #{name}, 0, 'ACTIVE')")
    int insertIgnore(@Param("id") String id, @Param("name") String name);

    /**
     * 原子更新引用热度计数（保底不低于 0）。
     */
    @Update("""
            UPDATE content_tag
            SET reference_count = GREATEST(0, reference_count + #{delta}),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{tagId}
            """)
    int updateReferenceCount(@Param("tagId") String tagId, @Param("delta") long delta);

    /**
     * 获取启用状态的热门标签排行。
     */
    @Select("SELECT * FROM content_tag WHERE status = 'ACTIVE' ORDER BY reference_count DESC LIMIT #{limit}")
    List<ContentTagPO> selectTopHot(@Param("limit") int limit);
}
