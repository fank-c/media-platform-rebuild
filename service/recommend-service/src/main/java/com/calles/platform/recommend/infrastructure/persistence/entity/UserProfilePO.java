package com.calles.platform.recommend.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推荐服务用户画像持久化实体 (PO)。
 *
 * <p>映射表名：{@code recommend_user_profile}。
 * 单行保存用户的当前向量、主题偏好、领域状态和近期观看短码序列，
 * 并支持 MyBatis-Plus 乐观锁版本号控制。</p>
 */
@Data
@TableName("recommend_user_profile")
public class UserProfilePO {

    /**
     * 用户账号全局唯一主键 ID。
     *
     * <p>业务含义与约束说明：
     * <ul>
     *   <li><b>来源对齐</b>：对应用户中心账户标识 (32位无短横线 UUID，与 {@code auth_account.id}、{@code user_profile.account_id} 强一致)；</li>
     *   <li><b>主键策略</b>：由外部业务调用方或上下文显式提供，采用 {@link IdType#INPUT} 手工赋值模式，非数据库自增；</li>
     *   <li><b>生命周期</b>：作为推荐画像表的聚簇索引，单用户全局唯一，贯穿用户生命周期全过程。</li>
     * </ul>
     * </p>
     */
    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    /** 当前用户即时检索向量浮点数组 JSON。 */
    @TableField("user_vector")
    private String userVector;

    /** 特征向量维度。 */
    @TableField("dimension")
    private Integer dimension;

    /** 用户向量最近更新时间。 */
    @TableField("vector_updated_at")
    private LocalDateTime vectorUpdatedAt;

    /** 细粒度主题偏好分快照 JSON。 */
    @TableField("topic_preferences")
    private String topicPreferences;

    /** 粗领域状态快照 JSON。 */
    @TableField("domain_states")
    private String domainStates;

    /** 近期观看视频短码列表 JSON。 */
    @TableField("recent_watch_vids")
    private String recentWatchVids;

    /** 画像乐观锁版本号。 */
    @Version
    @TableField("profile_version")
    private Long profileVersion;

    /** 创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
