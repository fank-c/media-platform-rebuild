package com.calles.platform.recommend.application.service;

import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;
import com.calles.platform.recommend.domain.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 用户明确屏蔽应用服务 (UserBlockApplicationService)。
 *
 * <p>负责用户主动屏蔽黑名单（视频、作者、主题标签）的增删查用例编排。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBlockApplicationService {

    private final UserBlockRepository userBlockRepository;

    /**
     * 添加明确屏蔽记录。
     *
     * @param userId 操作用户ID
     * @param blockType 屏蔽维度
     * @param targetId 目标标识
     * @param reason 原因说明
     * @return 屏蔽领域实体
     */
    @Transactional(rollbackFor = Exception.class)
    public UserBlock addBlock(String userId, BlockType blockType, String targetId, String reason) {
        // 步骤 1：入参前置校验
        if (userId == null || userId.isBlank() || blockType == null || targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("屏蔽参数不完整");
        }

        // 步骤 2：创建领域实体并幂等持久化
        UserBlock block = UserBlock.create(userId.trim(), blockType, targetId.trim(), reason);
        userBlockRepository.save(block);
        log.info("用户成功添加屏蔽门禁: userId={}, type={}, targetId={}", userId, blockType, targetId);
        return block;
    }

    /**
     * 撤销特定屏蔽记录。
     *
     * @param userId 操作用户ID
     * @param blockType 屏蔽维度
     * @param targetId 目标标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeBlock(String userId, BlockType blockType, String targetId) {
        if (userId == null || blockType == null || targetId == null) {
            return;
        }
        userBlockRepository.delete(userId.trim(), blockType, targetId.trim());
        log.info("用户成功撤销屏蔽门禁: userId={}, type={}, targetId={}", userId, blockType, targetId);
    }

    /**
     * 查询指定用户的所有屏蔽记录。
     *
     * @param userId 操作用户ID
     * @return 屏蔽记录列表
     */
    public List<UserBlock> listBlocks(String userId) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        return userBlockRepository.findByUserId(userId.trim());
    }
}
