package com.calles.platform.recommend.domain.repository;

import com.calles.platform.recommend.domain.model.VideoVector;
import java.util.Optional;

/**
 * 视频向量领域聚合根仓储接口契约。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：领域层与基础设施层持久化解耦桥梁；</li>
 *   <li><b>幂等与防重</b>：支持按内部全局视频 ID 与公开短码定向检索。</li>
 * </ul>
 * </p>
 */
public interface VideoVectorRepository {

    /**
     * 根据视频内部全局 ID 查找视频向量实体。
     *
     * @param videoId 视频全局 UUID
     * @return 实体可选容器
     */
    Optional<VideoVector> findByVideoId(String videoId);

    /**
     * 根据公开业务短码查找视频向量实体。
     *
     * @param vid 视频短码 (Base62)
     * @return 实体可选容器
     */
    Optional<VideoVector> findByVid(String vid);

    /**
     * 插入保存新建的视频向量实体。
     *
     * @param videoVector 领域实体
     */
    void save(VideoVector videoVector);

    /**
     * 更新已存在的视频向量实体。
     *
     * @param videoVector 领域实体
     */
    void update(VideoVector videoVector);
}
