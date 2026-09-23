package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.StarItemPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 收藏视频明细持久层 Mapper 接口。
 */
@Mapper
public interface StarItemMapper extends BaseMapper<StarItemPO> {

    /**
     * 忽略逻辑删除，物理检索指定收藏夹中特定视频的条目（用于唯一键防冲突与自愈复活）。
     *
     * @param folderId 收藏夹 ID
     * @param vid 视频业务公开短码
     * @return 匹配的条目 PO，若无则为 null
     */
    @Select("SELECT * FROM interaction_star_item WHERE folder_id = #{folderId} AND vid = #{vid} LIMIT 1")
    StarItemPO selectPhysicalByFolderAndVid(@Param("folderId") String folderId, @Param("vid") String vid);

    /**
     * 重新激活自愈已伪删除的收藏明细条目，置位 deleted = 0 并刷新创建时间。
     *
     * @param id 条目主键 UUID
     * @return 影响行数
     */
    @Update("UPDATE interaction_star_item SET deleted = 0, created_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int reviveById(@Param("id") String id);
}
