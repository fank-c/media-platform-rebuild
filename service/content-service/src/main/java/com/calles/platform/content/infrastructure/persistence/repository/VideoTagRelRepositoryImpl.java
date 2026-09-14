package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.tag.VideoTagRel;
import com.calles.platform.content.domain.repository.VideoTagRelRepository;
import com.calles.platform.content.infrastructure.persistence.entity.VideoTagRelPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoTagRelMapper;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 视频与标签关联仓储实现类 (VideoTagRelRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施层针对多对多关联实体 {@link VideoTagRel} 的持久化适配；</li>
 *   <li><b>协作对象</b>：委托 {@link VideoTagRelMapper} 操作 {@code video_tag_rel} 数据表；</li>
 *   <li><b>批量能力</b>：支持自动为关联记录填充 UUID 并执行批量插入。</li>
 * </ul>
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class VideoTagRelRepositoryImpl implements VideoTagRelRepository {

    /** 视频-标签关联 Mapper。 */
    private final VideoTagRelMapper videoTagRelMapper;

    @Override
    public int batchInsert(List<VideoTagRel> rels) {
        if (rels == null || rels.isEmpty()) {
            return 0;
        }
        // 步骤 1：遍历关联实体列表，缺失主键时补齐 UUID 并入库
        int count = 0;
        for (VideoTagRel rel : rels) {
            if (rel.getId() == null || rel.getId().isBlank()) {
                rel.setId(UUID.randomUUID().toString().replace("-", ""));
            }
            VideoTagRelPO po = VideoTagRelPO.fromDomain(rel);
            count += videoTagRelMapper.insert(po);
        }
        return count;
    }

    @Override
    public List<String> findTagIdsByVideoId(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return Collections.emptyList();
        }
        // 步骤 1：正向查询已绑定的标签 ID 列表
        return videoTagRelMapper.selectTagIdsByVideoId(videoId);
    }

    @Override
    public List<String> findVideoIdsByTagId(String tagId, int offset, int limit) {
        if (tagId == null || tagId.isBlank()) {
            return Collections.emptyList();
        }
        // 步骤 1：倒排分页检索打标的视频 ID 列表
        return videoTagRelMapper.selectVideoIdsByTagId(tagId, Math.max(0, offset), Math.max(1, limit));
    }

    @Override
    public int deleteByVideoId(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return 0;
        }
        // 步骤 1：物理删除指定视频名下的所有标签绑定关系
        return videoTagRelMapper.deleteByVideoId(videoId);
    }
}
