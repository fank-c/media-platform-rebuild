package com.calles.platform.content.domain.repository;

import com.calles.platform.content.domain.model.tag.VideoTagRel;
import java.util.List;

/**
 * 视频-标签关联仓储端口接口。
 */
public interface VideoTagRelRepository {

    /**
     * 批量保存关联记录。
     *
     * @param rels 关联列表
     * @return 影响行数
     */
    int batchInsert(List<VideoTagRel> rels);

    /**
     * 根据视频 ID 查询关联的全部标签 ID 列表。
     *
     * @param videoId 视频 ID
     * @return 关联的标签 ID (UUID) 列表
     */
    List<String> findTagIdsByVideoId(String videoId);

    /**
     * 倒排查询：根据标签 ID 分页查询关联的视频 ID 列表。
     *
     * @param tagId 标签 ID (UUID)
     * @param offset 偏移量
     * @param limit 数量限制
     * @return 关联的视频 ID 列表
     */
    List<String> findVideoIdsByTagId(String tagId, int offset, int limit);

    /**
     * 删除指定视频的全部标签关联。
     *
     * @param videoId 视频 ID
     * @return 影响行数
     */
    int deleteByVideoId(String videoId);
}
