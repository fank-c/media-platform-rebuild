package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.interaction.domain.model.star.StarItem;
import com.calles.platform.interaction.domain.repository.StarItemRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.StarItemPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.StarItemMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 收藏视频明细仓储实现类。
 */
@Repository
@RequiredArgsConstructor
public class StarItemRepositoryImpl implements StarItemRepository {

    private final StarItemMapper mapper;

    @Override
    public Optional<StarItem> findByFolderAndVid(String folderId, String vid) {
        if (folderId == null || vid == null) {
            return Optional.empty();
        }
        LambdaQueryWrapper<StarItemPO> wrapper = new LambdaQueryWrapper<StarItemPO>()
                .eq(StarItemPO::getFolderId, folderId)
                .eq(StarItemPO::getVid, vid);
        StarItemPO po = mapper.selectOne(wrapper);
        return Optional.ofNullable(po).map(StarItemPO::toDomain);
    }

    @Override
    public Optional<StarItem> findPhysicalByFolderAndVid(String folderId, String vid) {
        if (folderId == null || vid == null) {
            return Optional.empty();
        }
        StarItemPO po = mapper.selectPhysicalByFolderAndVid(folderId, vid);
        return Optional.ofNullable(po).map(StarItemPO::toDomain);
    }

    @Override
    public boolean isStarredByUser(String userId, String vid) {
        if (userId == null || vid == null) {
            return false;
        }
        LambdaQueryWrapper<StarItemPO> wrapper = new LambdaQueryWrapper<StarItemPO>()
                .eq(StarItemPO::getUserId, userId)
                .eq(StarItemPO::getVid, vid);
        return mapper.selectCount(wrapper) > 0;
    }

    @Override
    public List<StarItem> findByUserAndVid(String userId, String vid) {
        if (userId == null || vid == null) {
            return List.of();
        }
        LambdaQueryWrapper<StarItemPO> wrapper = new LambdaQueryWrapper<StarItemPO>()
                .eq(StarItemPO::getUserId, userId)
                .eq(StarItemPO::getVid, vid);
        List<StarItemPO> pos = mapper.selectList(wrapper);
        if (pos == null) {
            return List.of();
        }
        return pos.stream().map(StarItemPO::toDomain).toList();
    }

    @Override
    public List<StarItem> findByFolderId(String folderId, int offset, int limit) {
        if (folderId == null || folderId.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<StarItemPO> wrapper = new LambdaQueryWrapper<StarItemPO>()
                .eq(StarItemPO::getFolderId, folderId)
                .orderByDesc(StarItemPO::getCreatedAt)
                .last("LIMIT " + Math.max(0, offset) + ", " + Math.max(1, limit));
        List<StarItemPO> pos = mapper.selectList(wrapper);
        if (pos == null) {
            return List.of();
        }
        return pos.stream().map(StarItemPO::toDomain).toList();
    }

    @Override
    public long countByFolderId(String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return 0L;
        }
        LambdaQueryWrapper<StarItemPO> wrapper = new LambdaQueryWrapper<StarItemPO>()
                .eq(StarItemPO::getFolderId, folderId);
        return mapper.selectCount(wrapper);
    }

    @Override
    public void save(StarItem item) {
        if (item == null) {
            return;
        }
        mapper.insert(StarItemPO.fromDomain(item));
    }

    @Override
    public void revive(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        mapper.reviveById(id.trim());
    }

    @Override
    public int deleteByFolderAndVid(String folderId, String vid) {
        if (folderId == null || vid == null) {
            return 0;
        }
        LambdaQueryWrapper<StarItemPO> wrapper = new LambdaQueryWrapper<StarItemPO>()
                .eq(StarItemPO::getFolderId, folderId)
                .eq(StarItemPO::getVid, vid);
        return mapper.delete(wrapper);
    }

    @Override
    public int deleteByFolderAndVidAndUser(String folderId, String vid, String userId) {
        if (folderId == null || vid == null || userId == null) {
            return 0;
        }
        LambdaQueryWrapper<StarItemPO> wrapper = new LambdaQueryWrapper<StarItemPO>()
                .eq(StarItemPO::getFolderId, folderId)
                .eq(StarItemPO::getVid, vid)
                .eq(StarItemPO::getUserId, userId);
        return mapper.delete(wrapper);
    }

    @Override
    public int deleteByUserAndVid(String userId, String vid) {
        if (userId == null || vid == null) {
            return 0;
        }
        LambdaQueryWrapper<StarItemPO> wrapper = new LambdaQueryWrapper<StarItemPO>()
                .eq(StarItemPO::getUserId, userId)
                .eq(StarItemPO::getVid, vid);
        return mapper.delete(wrapper);
    }

    @Override
    public List<String> findVidsByFolderId(String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<StarItemPO> wrapper = new LambdaQueryWrapper<StarItemPO>()
                .select(StarItemPO::getVid)
                .eq(StarItemPO::getFolderId, folderId.trim());
        List<StarItemPO> pos = mapper.selectList(wrapper);
        if (pos == null) {
            return List.of();
        }
        return pos.stream()
                .map(StarItemPO::getVid)
                .filter(vid -> vid != null && !vid.isBlank())
                .distinct()
                .toList();
    }

    @Override
    public int deleteByFolderId(String folderId) {
        if (folderId == null || folderId.isBlank()) {
            return 0;
        }
        return mapper.deleteByFolderId(folderId.trim());
    }
}
