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

    /**
     * 查询指定时间窗口内有效播放量最高的视频公开短码列表。
     *
     * @param since 起始时间戳
     * @param limit 最大返回条数
     * @return 热门视频 vid 列表
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT vid
            FROM recommend_feedback_log
            WHERE action_type = 'PLAY' AND created_at >= #{since}
            GROUP BY vid
            ORDER BY COUNT(*) DESC
            LIMIT #{limit}
            """)
    java.util.List<String> selectTopVidsByPlays(
            @org.apache.ibatis.annotations.Param("since") java.time.LocalDateTime since,
            @org.apache.ibatis.annotations.Param("limit") int limit
    );
}
