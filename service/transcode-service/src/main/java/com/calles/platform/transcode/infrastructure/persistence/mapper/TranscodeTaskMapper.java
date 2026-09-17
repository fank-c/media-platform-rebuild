package com.calles.platform.transcode.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.transcode.infrastructure.persistence.entity.TranscodeTaskPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 转码工单 MyBatis-Plus 数据访问 Mapper 接口。
 */
@Mapper
public interface TranscodeTaskMapper extends BaseMapper<TranscodeTaskPO> {
}
