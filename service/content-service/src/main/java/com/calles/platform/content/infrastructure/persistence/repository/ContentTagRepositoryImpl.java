package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.tag.ContentTag;
import com.calles.platform.content.domain.repository.ContentTagRepository;
import com.calles.platform.content.infrastructure.persistence.entity.ContentTagPO;
import com.calles.platform.content.infrastructure.persistence.mapper.ContentTagMapper;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 标签全局仓储端口实现。
 */
@Repository
@RequiredArgsConstructor
public class ContentTagRepositoryImpl implements ContentTagRepository {

    private final ContentTagMapper contentTagMapper;

    @Override
    public Optional<ContentTag> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        ContentTagPO po = contentTagMapper.selectByName(name.trim());
        return Optional.ofNullable(po).map(ContentTagPO::toDomain);
    }

    @Override
    public Optional<ContentTag> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        ContentTagPO po = contentTagMapper.selectById(id);
        return Optional.ofNullable(po).map(ContentTagPO::toDomain);
    }

    @Override
    public List<ContentTag> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return contentTagMapper.selectBatchIds(ids).stream()
                .map(ContentTagPO::toDomain)
                .toList();
    }

    @Override
    public int insert(ContentTag tag) {
        if (tag.getId() == null || tag.getId().isBlank()) {
            tag.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        ContentTagPO po = ContentTagPO.fromDomain(tag);
        int rows = contentTagMapper.insert(po);
        return rows;
    }

    @Override
    public ContentTag findOrCreate(String name) {
        String cleanName = name.trim();
        ContentTagPO existing = contentTagMapper.selectByName(cleanName);
        if (existing != null) {
            return existing.toDomain();
        }
        String generatedId = UUID.randomUUID().toString().replace("-", "");
        contentTagMapper.insertIgnore(generatedId, cleanName);
        ContentTagPO created = contentTagMapper.selectByName(cleanName);
        return created.toDomain();
    }

    @Override
    public int updateReferenceCount(String tagId, long delta) {
        return contentTagMapper.updateReferenceCount(tagId, delta);
    }

    @Override
    public List<ContentTag> findTopHotTags(int limit) {
        return contentTagMapper.selectTopHot(limit).stream()
                .map(ContentTagPO::toDomain)
                .toList();
    }
}
