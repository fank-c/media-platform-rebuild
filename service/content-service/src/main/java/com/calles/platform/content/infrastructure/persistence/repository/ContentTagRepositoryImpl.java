package com.calles.platform.content.infrastructure.persistence.repository;

import com.calles.platform.content.domain.model.tag.ContentTag;
import com.calles.platform.content.domain.repository.ContentTagRepository;
import com.calles.platform.content.infrastructure.persistence.entity.ContentTagPO;
import com.calles.platform.content.infrastructure.persistence.mapper.ContentTagMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 标签全局仓储端口实现类 (ContentTagRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：基础设施层持久化实现，负责领域实体 {@link ContentTag} 与持久化对象 {@link ContentTagPO} 的双向转换；</li>
 *   <li><b>协作对象</b>：依赖 {@link ContentTagMapper} 执行数据库交互；</li>
 *   <li><b>幂等性</b>：在 {@link #findOrCreate(String)} 中利用底层数据库唯一键 (uk_tag_name) 与 {@code insertIgnore} 保证高并发安全。</li>
 * </ul>
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class ContentTagRepositoryImpl implements ContentTagRepository {

    /** 标签底层 MyBatis-Plus 数据访问 Mapper。 */
    private final ContentTagMapper contentTagMapper;

    @Override
    public Optional<ContentTag> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        // 步骤 1：去除首尾空格后按名称精确检索
        ContentTagPO po = contentTagMapper.selectByName(name.trim());
        // 步骤 2：转换为领域实体
        return Optional.ofNullable(po).map(ContentTagPO::toDomain);
    }

    @Override
    public Optional<ContentTag> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        // 步骤 1：按主键 ID 查询
        ContentTagPO po = contentTagMapper.selectById(id);
        // 步骤 2：转换为领域实体
        return Optional.ofNullable(po).map(ContentTagPO::toDomain);
    }

    @Override
    public List<ContentTag> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        // 步骤 1：批量主键检索并过滤转换
        return contentTagMapper.selectBatchIds(ids).stream()
                .map(ContentTagPO::toDomain)
                .toList();
    }

    @Override
    public int insert(ContentTag tag) {
        // 步骤 1：主键缺失时自动生成 UUID
        if (tag.getId() == null || tag.getId().isBlank()) {
            tag.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        // 步骤 2：转换为持久化 PO 并执行插入
        ContentTagPO po = ContentTagPO.fromDomain(tag);
        return contentTagMapper.insert(po);
    }

    @Override
    public ContentTag findOrCreate(String name) {
        String cleanName = name.trim();
        // 步骤 1：优先检索已有标签词条
        ContentTagPO existing = contentTagMapper.selectByName(cleanName);
        if (existing != null) {
            return existing.toDomain();
        }
        // 步骤 2：不存在时原子执行 INSERT IGNORE，防止并发唯一索引冲突
        String generatedId = UUID.randomUUID().toString().replace("-", "");
        contentTagMapper.insertIgnore(generatedId, cleanName);
        // 步骤 3：再次按名称检索出已成功入库（或并发插入）的最新记录并返回
        ContentTagPO created = contentTagMapper.selectByName(cleanName);
        return created.toDomain();
    }

    @Override
    public List<ContentTag> findByNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        // 步骤 1：去空并去除首尾空白
        List<String> cleanNames = names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (cleanNames.isEmpty()) {
            return Collections.emptyList();
        }
        // 步骤 2：批量检索已存在标签并转换为领域模型
        return contentTagMapper.selectByNames(cleanNames).stream()
                .map(ContentTagPO::toDomain)
                .toList();
    }

    @Override
    public List<ContentTag> findOrCreateBatch(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        // 步骤 1：清洗并去重标签名
        Set<String> cleanNames = names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());
        if (cleanNames.isEmpty()) {
            return Collections.emptyList();
        }

        // 步骤 2：先批量查询已有标签
        List<ContentTagPO> existing = contentTagMapper.selectByNames(cleanNames);
        Map<String, ContentTagPO> existingMap = existing.stream()
                .collect(Collectors.toMap(ContentTagPO::getName, po -> po, (a, b) -> a));

        // 步骤 3：识别缺失的标签并构建待插入持久化实体列表
        List<ContentTagPO> toInsert = new ArrayList<>();
        for (String name : cleanNames) {
            if (!existingMap.containsKey(name)) {
                toInsert.add(ContentTagPO.builder()
                        .id(UUID.randomUUID().toString().replace("-", ""))
                        .name(name)
                        .referenceCount(0L)
                        .status("ACTIVE")
                        .build());
            }
        }

        // 步骤 4：若存在新词条，一次性批量执行 INSERT IGNORE，并重新批量检索补全；否则直接返回已有实体
        if (!toInsert.isEmpty()) {
            contentTagMapper.batchInsertIgnore(toInsert);
            return contentTagMapper.selectByNames(cleanNames).stream()
                    .map(ContentTagPO::toDomain)
                    .toList();
        }
        return existing.stream()
                .map(ContentTagPO::toDomain)
                .toList();
    }

    @Override
    public int updateReferenceCount(String tagId, long delta) {
        // 步骤 1：执行原子增减，保证非负
        return contentTagMapper.updateReferenceCount(tagId, delta);
    }

    @Override
    public int batchUpdateReferenceCount(Collection<String> tagIds, long delta) {
        if (tagIds == null || tagIds.isEmpty() || delta == 0) {
            return 0;
        }
        // 步骤 1：主键过滤去空并强制升序排列，统一各并发事务加锁顺序以彻底避免 MySQL 行锁死锁
        List<String> sortedIds = tagIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (sortedIds.isEmpty()) {
            return 0;
        }
        // 步骤 2：执行批量原子增减
        return contentTagMapper.batchUpdateReferenceCount(sortedIds, delta);
    }

    @Override
    public List<ContentTag> findTopHotTags(int limit) {
        // 步骤 1：检索高频热门词条并转为领域模型
        return contentTagMapper.selectTopHot(limit).stream()
                .map(ContentTagPO::toDomain)
                .toList();
    }
}
