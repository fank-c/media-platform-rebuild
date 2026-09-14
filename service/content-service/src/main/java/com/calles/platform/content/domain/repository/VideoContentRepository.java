package com.calles.platform.content.domain.repository;

import com.calles.platform.content.domain.model.video.VideoContent;
import java.util.Optional;

/**
 * 视频内容聚合根仓储端口接口 (Domain Repository Interface)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：定义视频内容领域核心聚合的持久化契约；</li>
 *   <li><b>并发控制</b>：定义包含基于版本号 {@code revision} 的乐观锁更新接口，杜绝并发覆盖；</li>
 *   <li><b>协作对象</b>：由基础设施层 {@link com.calles.platform.content.infrastructure.persistence.repository.VideoContentRepositoryImpl} 映射物理数据表。</li>
 * </ul>
 * </p>
 */
public interface VideoContentRepository {

    /**
     * 持久化新增视频草稿或发布聚合根。
     *
     * @param video 待保存的视频领域聚合根实体
     * @return 影响的数据库记录行数
     */
    int insert(VideoContent video);

    /**
     * 携带乐观锁版本 revision 更新视频记录（自动 revision + 1）。
     *
     * @param video 待更新的视频实体
     * @return 影响行数；返回 0 表示发生并发版本冲突或该记录已被删除
     */
    int updateById(VideoContent video);

    /**
     * 根据内部主键 UUID 查询有效（未被逻辑删除）的视频聚合根。
     *
     * @param id 内部全局主键 ID (UUID 32位)
     * @return 包含领域实体的 {@link Optional}，未查到或已逻辑删除则返回 empty
     */
    Optional<VideoContent> findById(String id);

    /**
     * 根据对外公开业务短码 vid 查询有效（未被逻辑删除）的视频内容。
     *
     * @param vid 24 位高熵业务编码 (如 cv05hG9Kq2RtLw7XbPmZv4Ya)
     * @return 包含领域实体的 {@link Optional}，未查到或已删除则返回 empty
     */
    Optional<VideoContent> findByVid(String vid);

    /**
     * 逻辑删除指定内部主键 ID 的视频内容 (置 deleted=1)。
     *
     * @param id 视频内部全局主键 ID
     * @return 影响行数
     */
    int deleteById(String id);
}
