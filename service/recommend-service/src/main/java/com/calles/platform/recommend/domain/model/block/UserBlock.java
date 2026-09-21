package com.calles.platform.recommend.domain.model.block;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 用户明确屏蔽约束领域实体。
 *
 * <p>表示用户对特定视频、创作者或主题标签的主动拉黑记录。
 * 属于硬性约束门禁，优先级最高，不可被算法打分抵消，支持用户撤销与按用户批量过滤查询。</p>
 */
@Getter
public class UserBlock {

    /** 屏蔽记录主键 UUID (32位无连字符)。 */
    private final String id;

    /** 归属用户账号全局唯一标识。 */
    private final String userId;

    /** 屏蔽维度类型 (视频、创作者、主题标签)。 */
    private final BlockType blockType;

    /** 屏蔽目标标识 (vid、authorId 或 tagId)。 */
    private final String targetId;

    /** 屏蔽原因说明 (可选，如 NOT_INTERESTED、OFFENSIVE)。 */
    private final String reason;

    /** 屏蔽创建时间。 */
    private final LocalDateTime createdAt;

    /**
     * 全参构造方法，用于仓储还原实体。
     *
     * @param id 主键 UUID
     * @param userId 用户账号ID
     * @param blockType 屏蔽类型
     * @param targetId 目标标识
     * @param reason 屏蔽原因
     * @param createdAt 创建时间
     */
    public UserBlock(String id, String userId, BlockType blockType, String targetId, String reason, LocalDateTime createdAt) {
        // 步骤 1：核心参数前置校验，防止非空属性破损
        this.id = Objects.requireNonNull(id, "屏蔽主键ID不能为空");
        this.userId = Objects.requireNonNull(userId, "用户ID不能为空");
        this.blockType = Objects.requireNonNull(blockType, "屏蔽类型不能为空");
        this.targetId = Objects.requireNonNull(targetId, "屏蔽目标标识不能为空");
        this.reason = reason;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    /**
     * 工厂方法：新建明确屏蔽记录。
     *
     * @param userId 用户账号ID
     * @param blockType 屏蔽类型
     * @param targetId 目标标识
     * @param reason 屏蔽原因
     * @return 新建的 UserBlock 实体实例
     */
    public static UserBlock create(String userId, BlockType blockType, String targetId, String reason) {
        // 步骤 1：生成标准 32 位无短横线 UUID
        String generatedId = UUID.randomUUID().toString().replace("-", "");
        return new UserBlock(generatedId, userId, blockType, targetId, reason, LocalDateTime.now());
    }

    /**
     * 判定候选目标是否命中当前屏蔽规则。
     *
     * @param candidateType 候选维度类型
     * @param candidateTargetId 候选目标标识
     * @return true 若命中当前屏蔽规则，false 否则
     */
    public boolean matches(BlockType candidateType, String candidateTargetId) {
        if (this.blockType != candidateType) {
            return false;
        }
        return this.targetId.equals(candidateTargetId);
    }
}
