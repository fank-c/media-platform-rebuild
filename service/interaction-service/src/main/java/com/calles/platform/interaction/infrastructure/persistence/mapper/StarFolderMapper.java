package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.StarFolderPO;
import org.apache.ibatis.annotations.Mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 收藏夹持久层 Mapper 接口。
 */
@Mapper
public interface StarFolderMapper extends BaseMapper<StarFolderPO> {

    /**
     * 逻辑删除收藏夹，将 status 置为 0，deleted 置为 1，并更新修改时间。
     *
     * @param id 收藏夹 ID
     * @return 影响行数
     */
    @Update("UPDATE interaction_star_folder SET status = 0, deleted = 1, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND deleted = 0")
    int deleteFolderById(@Param("id") String id);

    /**
     * 采用当前读（Locking Read FOR UPDATE）查询用户活跃的默认收藏夹。
     *
     * <p>绕过 MySQL REPEATABLE READ 隔离级别下的 MVCC 快照读限制，
     * 确保在并发初始化唯一键冲突时能即时读取已由胜出事务提交的最新行数据。</p>
     *
     * @param userId 用户账号 ID
     * @return 默认收藏夹持久化实体
     */
    @org.apache.ibatis.annotations.Select("SELECT * FROM interaction_star_folder WHERE user_id = #{userId} AND is_default = 1 AND status = 1 AND deleted = 0 LIMIT 1 FOR UPDATE")
    StarFolderPO selectDefaultByUserIdForUpdate(@Param("userId") String userId);
}
