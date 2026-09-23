package com.calles.platform.interaction.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.interaction.infrastructure.persistence.entity.VideoLikePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 视频点赞持久层 Mapper 接口。
 */
@Mapper
public interface VideoLikeMapper extends BaseMapper<VideoLikePO> {
}
