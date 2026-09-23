package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.InteractionShareRecordPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 视频分享幂等记录数据访问 Mapper。
 */
@Mapper
public interface InteractionShareRecordMapper extends BaseMapper<InteractionShareRecordPO> {
}
