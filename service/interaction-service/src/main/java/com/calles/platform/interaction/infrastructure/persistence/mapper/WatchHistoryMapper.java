package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.WatchHistoryPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 观看历史与断点持久层 Mapper 接口。
 */
@Mapper
public interface WatchHistoryMapper extends BaseMapper<WatchHistoryPO> {
}
