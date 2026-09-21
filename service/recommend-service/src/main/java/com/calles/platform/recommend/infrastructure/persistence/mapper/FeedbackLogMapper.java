package com.calles.platform.recommend.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.calles.platform.recommend.infrastructure.persistence.entity.FeedbackLogPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 行为反馈流水持久化映射器 (FeedbackLogMapper)。
 *
 * <p>独占读写 {@code recommend_feedback_log} 表。</p>
 */
@Mapper
public interface FeedbackLogMapper extends BaseMapper<FeedbackLogPO> {
}
