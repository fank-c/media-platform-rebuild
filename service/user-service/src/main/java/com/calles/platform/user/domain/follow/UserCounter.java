package com.calles.platform.user.domain.follow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户关系统计快照实体类，映射数据表 {@code user_counter}。
 * 独立承载高并发关注/被关注计数更新，彻底与个人基本资料及乐观锁解耦。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_counter")
public class UserCounter {

    /** 用户账号ID（逻辑关联 {@code user_profile.account_id}）。 */
    @TableId(value = "account_id", type = IdType.INPUT)
    private String accountId;

    /** 关注数（我关注的人数）。 */
    @TableField("following_count")
    private Long followingCount;

    /** 粉丝数（关注我的人数）。 */
    @TableField("follower_count")
    private Long followerCount;

    /** 记录创建时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 记录最后更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
