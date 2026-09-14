package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.tag.VideoTagRel;
import com.calles.platform.content.domain.repository.VideoTagRelRepository;
import com.calles.platform.content.infrastructure.persistence.entity.VideoTagRelPO;
import com.calles.platform.content.infrastructure.persistence.mapper.VideoTagRelMapper;
import java.util.ArrayList;
import java.util.Collection;
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
 *   <li><b>批量能力</b>：支持自动为关联记录填充 UUID 并执行真正的一次性批量插入与精准解绑。</li>
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
        // 步骤 1：遍历关联实体列表，缺失主键时补齐 UUID 并转换为持久化 PO 列表
        List<VideoTagRelPO> pos = new ArrayList<>(rels.size());
        for (VideoTagRel rel : rels) {
            if (rel.getId() == null || rel.getId().isBlank()) {
                rel.setId(UUID.randomUUID().toString().replace("-", ""));
            }
            pos.add(VideoTagRelPO.fromDomain(rel));
        }
        // 步骤 2：通过批量 SQL 一次性落库
        return videoTagRelMapper.batchInsert(pos);
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

    @Override
    public int deleteByVideoIdAndTagIds(String videoId, Collection<String> tagIds) {
        if (videoId == null || videoId.isBlank() || tagIds == null || tagIds.isEmpty()) {
            return 0;
        }
        // 步骤 1：过滤去空并强制升序排列，统一各并发事务加锁顺序防死锁
        List<String> sortedTagIds = tagIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (sortedTagIds.isEmpty()) {
            return 0;
        }
        // 步骤 2：精准物理批量删除指定关联记录
        return videoTagRelMapper.deleteByVideoIdAndTagIds(videoId, sortedTagIds);
    }
}
