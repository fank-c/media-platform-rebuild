package com.calles.platform.interaction.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.interaction.domain.model.star.StarFolder;
import com.calles.platform.interaction.domain.repository.StarFolderRepository;
import com.calles.platform.interaction.infrastructure.persistence.entity.StarFolderPO;
import com.calles.platform.interaction.infrastructure.persistence.mapper.StarFolderMapper;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 收藏夹仓储实现类。
 */
@Repository
@RequiredArgsConstructor
public class StarFolderRepositoryImpl implements StarFolderRepository {

    private final StarFolderMapper mapper;

    @Override
    public Optional<StarFolder> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        StarFolderPO po = mapper.selectById(id);
        return Optional.ofNullable(po).map(StarFolderPO::toDomain);
    }

    @Override
    public Optional<StarFolder> findDefaultByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<StarFolderPO> wrapper = new LambdaQueryWrapper<StarFolderPO>()
                .eq(StarFolderPO::getUserId, userId)
                .eq(StarFolderPO::getIsDefault, 1)
                .eq(StarFolderPO::getStatus, 1)
                .last("LIMIT 1");
        StarFolderPO po = mapper.selectOne(wrapper);
        return Optional.ofNullable(po).map(StarFolderPO::toDomain);
    }

    @Override
    public Optional<StarFolder> findDefaultByUserIdForUpdate(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        StarFolderPO po = mapper.selectDefaultByUserIdForUpdate(userId.trim());
        return Optional.ofNullable(po).map(StarFolderPO::toDomain);
    }

    @Override
    public List<StarFolder> findActiveByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<StarFolderPO> wrapper = new LambdaQueryWrapper<StarFolderPO>()
                .eq(StarFolderPO::getUserId, userId)
                .eq(StarFolderPO::getStatus, 1)
                .orderByDesc(StarFolderPO::getIsDefault)
                .orderByDesc(StarFolderPO::getCreatedAt);
        List<StarFolderPO> pos = mapper.selectList(wrapper);
        if (pos == null) {
            return List.of();
        }
        return pos.stream().map(StarFolderPO::toDomain).toList();
    }

    @Override
    public void save(StarFolder folder) {
        if (folder == null) {
            return;
        }
        mapper.insert(StarFolderPO.fromDomain(folder));
    }

    @Override
    public void update(StarFolder folder) {
        if (folder == null) {
            return;
        }
        mapper.updateById(StarFolderPO.fromDomain(folder));
    }

    @Override
    public boolean existsByUserIdAndTitle(String userId, String title) {
        if (userId == null || userId.isBlank() || title == null || title.isBlank()) {
            return false;
        }
        LambdaQueryWrapper<StarFolderPO> wrapper = new LambdaQueryWrapper<StarFolderPO>()
                .eq(StarFolderPO::getUserId, userId.trim())
                .eq(StarFolderPO::getTitle, title.trim())
                .eq(StarFolderPO::getStatus, 1);
        return mapper.selectCount(wrapper) > 0;
    }

    @Override
    public boolean existsByUserIdAndTitleExcludingId(String userId, String title, String excludeFolderId) {
        if (userId == null || userId.isBlank() || title == null || title.isBlank()) {
            return false;
        }
        LambdaQueryWrapper<StarFolderPO> wrapper = new LambdaQueryWrapper<StarFolderPO>()
                .eq(StarFolderPO::getUserId, userId.trim())
                .eq(StarFolderPO::getTitle, title.trim())
                .eq(StarFolderPO::getStatus, 1)
                .ne(excludeFolderId != null && !excludeFolderId.isBlank(), StarFolderPO::getId, excludeFolderId);
        return mapper.selectCount(wrapper) > 0;
    }

    @Override
    public void deleteById(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        mapper.deleteFolderById(id.trim());
    }
}
