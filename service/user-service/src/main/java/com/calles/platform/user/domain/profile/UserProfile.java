package com.calles.platform.user.domain.profile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户资料服务独占的资料信息。
 * accountId 仅逻辑关联认证主体 ID，资料服务不得直接查询认证表。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_profile")
public class UserProfile {

    /**
     * 认证主体 UUID 的逻辑关联键，由认证服务生成后传入，不建立跨服务外键或直接查询认证表。
     */
    @TableId(value = "account_id", type = IdType.INPUT)
    private String accountId;

    /**
     * 用户对外展示的昵称，可为空。
     */
    @TableField("nickname")
    private String nickname;

    /**
     * 用户头像的资源地址，可为空。
     */
    @TableField("avatar_url")
    private String avatarUrl;

    /**
     * 用户自行维护的个人简介，可为空。
     */
    @TableField("bio")
    private String bio;

    /**
     * 用户自行填写的城市文本，可为空，不用于精确定位或权限判断。
     */
    @TableField("city")
    private String city;

    /**
     * 资料性别字节值；当前不施加枚举或业务含义约束。
     */
    @TableField("gender")
    private Byte gender;

    /**
     * 用户填写的出生日期，可为空。
     */
    @TableField("birthday")
    private LocalDate birthday;

    /**
     * 资料在用户域内的可用状态；不承担认证账户的登录和授权状态。
     */
    @TableField("status")
    private ProfileStatus status;

    /**
     * 逻辑删除标记：0 表示有效记录，1 表示已删除。MyBatis-Plus 查询会自动排除已删除资料。
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /**
     * 资料乐观并发版本；每次成功编辑递增，初始化和重复消息不递增。
     */
    @TableField("revision")
    private Long revision;

    /**
     * 资料创建时间，由持久化层写入。
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 资料最后更新时间，由持久化层维护。
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
