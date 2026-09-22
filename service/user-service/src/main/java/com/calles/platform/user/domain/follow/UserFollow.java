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
 * 用户关注拓扑关系聚合根，映射数据表 {@code user_follow}。
 * 表达发起关注人（{@code userId}）对目标被关注人（{@code followId}）的单向指向。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_follow")
public class UserFollow {

    /** 自增物理主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关注者用户ID（动作发起人）。 */
    @TableField("user_id")
    private String userId;

    /** 被关注者用户ID（目标用户）。 */
    @TableField("follow_id")
    private String followId;

    /** 关注状态：1=已关注，0=已取消。 */
    @TableField("follow_status")
    private Integer followStatus;

    /** 初次关注建立时间。 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 最后状态更新时间。 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 判断当前实体是否处于有效关注状态。
     *
     * @return 若处于有效关注返回 true
     */
    public boolean isCurrentlyFollowing() {
        return followStatus != null && followStatus == FollowStatus.FOLLOWING.getCode();
    }
}
