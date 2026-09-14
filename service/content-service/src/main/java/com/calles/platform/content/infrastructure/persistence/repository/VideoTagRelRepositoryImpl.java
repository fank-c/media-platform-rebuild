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
 * 视频与标签关联仓储端口实现。
 */
@Repository
@RequiredArgsConstructor
public class VideoTagRelRepositoryImpl implements VideoTagRelRepository {

    private final VideoTagRelMapper videoTagRelMapper;

    @Override
    public int batchInsert(List<VideoTagRel> rels) {
        if (rels == null || rels.isEmpty()) {
            return 0;
        }
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
        return videoTagRelMapper.selectTagIdsByVideoId(videoId);
    }

    @Override
    public List<String> findVideoIdsByTagId(String tagId, int offset, int limit) {
        if (tagId == null || tagId.isBlank()) {
            return Collections.emptyList();
        }
        return videoTagRelMapper.selectVideoIdsByTagId(tagId, Math.max(0, offset), Math.max(1, limit));
    }

    @Override
    public int deleteByVideoId(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return 0;
        }
        return videoTagRelMapper.deleteByVideoId(videoId);
    }
}
