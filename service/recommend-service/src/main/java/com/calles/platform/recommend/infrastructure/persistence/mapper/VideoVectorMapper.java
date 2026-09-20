package com.calles.platform.recommend.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.recommend.infrastructure.persistence.entity.VideoVectorPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 视频特征向量数据库访问持久化接口 (VideoVectorMapper)。
 */
@Mapper
public interface VideoVectorMapper extends BaseMapper<VideoVectorPO> {
}
