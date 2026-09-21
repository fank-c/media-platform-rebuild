package com.calles.platform.recommend.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐服务用户明确屏蔽持久化实体 (PO)。
 *
 * <p>映射表名：{@code recommend_user_block}。</p>
 */
@Data
@TableName("recommend_user_block")
public class UserBlockPO {

    /**
     * 用户明确屏蔽约束记录唯一主键 ID。
     *
     * <p>业务含义与约束说明：
     * <ul>
     *   <li><b>格式规范</b>：32 位无连字符标准 UUID 字符串；</li>
     *   <li><b>主键策略</b>：由应用层在构建领域实体时显式生成注入，采用 {@link IdType#INPUT} 模式，非数据库自增；</li>
     *   <li><b>防重约束</b>：作为底层物理主键，与业务唯一索引 {@code uk_rub_user_block_target (user_id, block_type, target_id)} 配合实现强幂等。</li>
     * </ul>
     * </p>
     */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    /** 用户账号ID。 */
    @TableField("user_id")
    private String userId;

    /** 屏蔽类型: VIDEO, AUTHOR, TOPIC。 */
    @TableField("block_type")
    private String blockType;

    /** 屏蔽目标标识 (具体 vid / author_id / tag_id)。 */
    @TableField("target_id")
    private String targetId;

    /** 屏蔽原因说明。 */
    @TableField("reason")
    private String reason;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
