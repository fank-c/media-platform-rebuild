package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.StarFolderPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收藏夹持久层 Mapper 接口。
 */
@Mapper
public interface StarFolderMapper extends BaseMapper<StarFolderPO> {
}
