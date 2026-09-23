package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.StarItemPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收藏视频明细持久层 Mapper 接口。
 */
@Mapper
public interface StarItemMapper extends BaseMapper<StarItemPO> {
}
