package com.calles.platform.recommend.domain.repository;

import com.calles.platform.recommend.domain.model.block.BlockType;
import com.calles.platform.recommend.domain.model.block.UserBlock;

import java.util.List;

/**
 * 用户明确屏蔽约束仓储接口。
 *
 * <p>提供用户黑名单的添加、撤销与批量过滤判定契约。</p>
 */
public interface UserBlockRepository {

    /**
     * 保存一条屏蔽记录 (若已存在相同维度目标则忽略或更新)。
     *
     * @param block 屏蔽实体
     */
    void save(UserBlock block);

    /**
     * 撤销特定屏蔽记录。
     *
     * @param userId 用户账号ID
     * @param blockType 屏蔽维度类型
     * @param targetId 目标标识
     */
    void delete(String userId, BlockType blockType, String targetId);

    /**
     * 查询指定用户的所有屏蔽记录列表。
     *
     * @param userId 用户账号ID
     * @return 屏蔽实体列表
     */
    List<UserBlock> findByUserId(String userId);

    /**
     * 查询指定用户在特定类型下的全部屏蔽目标集合。
     *
     * @param userId 用户账号ID
     * @param blockType 屏蔽类型
     * @return 目标标识列表 (如所有被拉黑的 vid、authorId 或 tagId)
     */
    List<String> findTargetIdsByUserIdAndType(String userId, BlockType blockType);

    /**
     * 判定指定目标是否已被用户拉黑。
     *
     * @param userId 用户账号ID
     * @param blockType 屏蔽类型
     * @param targetId 目标标识
     * @return true 若已拉黑，false 否则
     */
    boolean isBlocked(String userId, BlockType blockType, String targetId);
}
