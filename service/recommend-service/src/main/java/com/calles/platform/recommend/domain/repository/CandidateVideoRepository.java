package com.calles.platform.recommend.domain.repository;

import com.calles.platform.recommend.domain.model.CandidateStatus;
import com.calles.platform.recommend.domain.model.CandidateVideo;
import java.util.Optional;

/**
 * 推荐候选池持久化仓储契约接口 (CandidateVideoRepository)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：领域层针对推荐候选视频的持久化契约，隔离底层 MyBatis-Plus 数据库实现；</li>
 *   <li><b>协作对象</b>：由基础设施层 {@link com.calles.platform.recommend.infrastructure.persistence.repository.CandidateVideoRepositoryImpl} 实现；</li>
 *   <li><b>核心契约</b>：包含安全插入、按 videoId/vid 查询、状态原子变更等。</li>
 * </ul>
 * </p>
 */
public interface CandidateVideoRepository {

    /**
     * 保存或幂等更新推荐候选视频记录。
     *
     * @param candidate 候选视频领域实体
     * @return 影响的数据库行数
     */
    int insert(CandidateVideo candidate);

    /**
     * 根据视频内部全局 ID 查找候选实体。
     *
     * @param videoId 视频内部全局主键
     * @return 包含候选实体的 {@link Optional}，不存在时返回 empty
     */
    Optional<CandidateVideo> findByVideoId(String videoId);

    /**
     * 根据业务公开短码 vid 查找候选实体。
     *
     * @param vid 视频公开短码
     * @return 包含候选实体的 {@link Optional}，不存在时返回 empty
     */
    Optional<CandidateVideo> findByVid(String vid);

    /**
     * 原子更新指定视频的推荐候选状态（如 OFFLINE、BANNED）。
     *
     * @param videoId 视频内部全局主键
     * @param status 目标状态枚举
     * @return 影响的行数
     */
    int updateStatusByVideoId(String videoId, CandidateStatus status);
}
