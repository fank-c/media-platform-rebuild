package com.calles.platform.content.domain.repository;

import com.calles.platform.content.domain.model.tag.VideoTagRel;
import java.util.Collection;
import java.util.List;

/**
 * 视频与标签关联仓储端口接口 (Domain Repository Interface)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：视频与标签多对多关联关系统计与关系表维护契约；</li>
 *   <li><b>协作对象</b>：由基础设施层 {@link com.calles.platform.content.infrastructure.persistence.repository.VideoTagRelRepositoryImpl} 实现；</li>
 *   <li><b>能力覆盖</b>：支持批量绑定、正向按视频查标签、反向按标签分页拉取视频 ID 以及全量/精准批量解绑。</li>
 * </ul>
 * </p>
 */
public interface VideoTagRelRepository {

    /**
     * 批量持久化保存视频与标签关联绑定记录。
     *
     * @param rels 待插入的关联实体列表
     * @return 成功插入的记录总行数
     */
    int batchInsert(List<VideoTagRel> rels);

    /**
     * 正向查询：根据视频 ID 查询其绑定的全部标签 ID 列表。
     *
     * @param videoId 视频全局内部主键 ID
     * @return 绑定的标签 ID (UUID) 列表（按绑定先后正序排列）
     */
    List<String> findTagIdsByVideoId(String videoId);

    /**
     * 倒排查询：根据标签 ID 分页检索打上该标签的视频 ID 列表。
     *
     * @param tagId 标签全局主键 ID (UUID)
     * @param offset 物理分页偏移量 (非负)
     * @param limit 本次拉取的数量上限
     * @return 关联的视频 ID (UUID) 列表
     */
    List<String> findVideoIdsByTagId(String tagId, int offset, int limit);

    /**
     * 物理删除指定视频的所有标签关联绑定记录。
     *
     * @param videoId 视频全局内部主键 ID
     * @return 删除的关联记录行数
     */
    int deleteByVideoId(String videoId);

    /**
     * 精准批量物理解除指定视频与某些特定标签的关联绑定记录。
     *
     * @param videoId 视频全局内部主键 ID
     * @param tagIds 待解绑的标签主键 ID (UUID) 集合
     * @return 实际删除的关联记录行数
     */
    int deleteByVideoIdAndTagIds(String videoId, Collection<String> tagIds);
}
