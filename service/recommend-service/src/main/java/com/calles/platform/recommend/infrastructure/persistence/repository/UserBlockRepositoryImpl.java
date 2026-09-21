package com.calles.platform.recommend.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import com.calles.platform.recommend.infrastructure.persistence.entity.UserBlockPO;
import com.calles.platform.recommend.infrastructure.persistence.mapper.UserBlockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户明确屏蔽约束领域仓储实现类 (UserBlockRepositoryImpl)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>防腐隔离</b>：负责 {@link UserBlock} 领域实体与 {@link UserBlockPO} 数据库实体间的映射；</li>
 *   <li><b>防重复屏蔽</b>：基于 {@code (user_id, block_type, target_id)} 唯一索引保证幂等；</li>
 *   <li><b>高速门禁查询</b>：提供按用户和维度的快速目标标识提取与存在性校验。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class UserBlockRepositoryImpl implements UserBlockRepository {

    private final UserBlockMapper mapper;

    @Override
    public void save(UserBlock block) {
        if (block == null) {
            return;
        }

        // 步骤 1：查询是否已存在相同屏蔽记录，避免唯一索引冲突
        LambdaQueryWrapper<UserBlockPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBlockPO::getUserId, block.getUserId())
                .eq(UserBlockPO::getBlockType, block.getBlockType().getCode())
                .eq(UserBlockPO::getTargetId, block.getTargetId());

        UserBlockPO existing = mapper.selectOne(wrapper);
        if (existing == null) {
            UserBlockPO po = toPO(block);
            mapper.insert(po);
            log.debug("新增用户屏蔽记录: userId={}, type={}, targetId={}",
                    block.getUserId(), block.getBlockType(), block.getTargetId());
        }
    }

    @Override
    public void delete(String userId, BlockType blockType, String targetId) {
        if (userId == null || blockType == null || targetId == null) {
            return;
        }
        LambdaQueryWrapper<UserBlockPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBlockPO::getUserId, userId)
                .eq(UserBlockPO::getBlockType, blockType.getCode())
                .eq(UserBlockPO::getTargetId, targetId);
        mapper.delete(wrapper);
        log.debug("撤销用户屏蔽记录: userId={}, type={}, targetId={}", userId, blockType, targetId);
    }

    @Override
    public List<UserBlock> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<UserBlockPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBlockPO::getUserId, userId)
                .orderByDesc(UserBlockPO::getCreatedAt);
        List<UserBlockPO> pos = mapper.selectList(wrapper);
        return pos.stream().map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<String> findTargetIdsByUserIdAndType(String userId, BlockType blockType) {
        if (userId == null || blockType == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<UserBlockPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(UserBlockPO::getTargetId)
                .eq(UserBlockPO::getUserId, userId)
                .eq(UserBlockPO::getBlockType, blockType.getCode());
        List<UserBlockPO> pos = mapper.selectList(wrapper);
        return pos.stream().map(UserBlockPO::getTargetId).collect(Collectors.toList());
    }

    @Override
    public boolean isBlocked(String userId, BlockType blockType, String targetId) {
        if (userId == null || blockType == null || targetId == null) {
            return false;
        }
        LambdaQueryWrapper<UserBlockPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserBlockPO::getUserId, userId)
                .eq(UserBlockPO::getBlockType, blockType.getCode())
                .eq(UserBlockPO::getTargetId, targetId);
        return mapper.selectCount(wrapper) > 0;
    }

    private UserBlock toDomain(UserBlockPO po) {
        if (po == null) {
            return null;
        }
        return new UserBlock(
                po.getId(),
                po.getUserId(),
                BlockType.fromCode(po.getBlockType()),
                po.getTargetId(),
                po.getReason(),
                po.getCreatedAt()
        );
    }

    private UserBlockPO toPO(UserBlock domain) {
        UserBlockPO po = new UserBlockPO();
        po.setId(domain.getId());
        po.setUserId(domain.getUserId());
        po.setBlockType(domain.getBlockType().getCode());
        po.setTargetId(domain.getTargetId());
        po.setReason(domain.getReason());
        po.setCreatedAt(domain.getCreatedAt());
        return po;
    }
}
