package com.calles.platform.recommend.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.recommend.infrastructure.persistence.entity.UserBlockPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户明确屏蔽持久化映射器 (UserBlockMapper)。
 *
 * <p>独占读写 {@code recommend_user_block} 表。</p>
 */
@Mapper
public interface UserBlockMapper extends BaseMapper<UserBlockPO> {
}
