package com.calles.platform.recommend.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.recommend.infrastructure.persistence.entity.UserProfilePO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户画像持久化映射器 (UserProfileMapper)。
 *
 * <p>独占读写 {@code recommend_user_profile} 表。</p>
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfilePO> {
}
