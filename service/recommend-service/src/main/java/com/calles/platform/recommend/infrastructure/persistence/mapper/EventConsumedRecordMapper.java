package com.calles.platform.recommend.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.recommend.infrastructure.persistence.entity.EventConsumedRecordPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 互动事件消费幂等记录 MyBatis-Plus Mapper 接口。
 */
@Mapper
public interface EventConsumedRecordMapper extends BaseMapper<EventConsumedRecordPO> {
}
