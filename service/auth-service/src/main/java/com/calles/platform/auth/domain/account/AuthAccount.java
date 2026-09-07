package com.calles.platform.auth.domain.account;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 认证服务独占的账户凭据与授权状态。
 * passwordHash 仅可承载 BCrypt 哈希，禁止记录或输出密码明文。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("auth_account")
public class AuthAccount {

    /**
     * 认证服务使用 MyBatis-Plus ASSIGN_UUID 生成的 32 位字符串账户主键。
     */
    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 用于身份校验的唯一登录名，不承载展示昵称。
     */
    @TableField("login_name")
    private String loginName;

    /**
     * 仅保存 BCrypt 密码哈希；调用方不得记录、序列化或输出其明文来源。
     */
    @TableField("password_hash")
    private String passwordHash;

    /**
     * 账户在认证域内的授权角色。
     */
    @TableField("role")
    private AccountRole role;

    /**
     * 账户可用状态，禁用状态不得用于签发新的认证凭据。
     */
    @TableField("status")
    private AccountStatus status;

    /**
     * 逻辑删除标记：0 表示有效记录，1 表示已删除。MyBatis-Plus 会自动过滤已删除账户，
     * 删除认证账户不会直接清除审计所需的凭据历史。
     */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    /**
     * 账户创建时间，由持久化层写入。
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 账户最后更新时间，由持久化层维护。
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
