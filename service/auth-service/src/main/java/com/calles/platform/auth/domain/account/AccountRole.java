package com.calles.platform.auth.domain.account;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 认证账户在本服务内承担的授权角色。
 */
@Getter
@RequiredArgsConstructor
public enum AccountRole {

    /** 普通用户，只拥有用户侧授权范围。 */
    USER("USER"),
    /** 管理员，令牌中的主体类型为 {@code admin}。 */
    ADMIN("ADMIN");

    /**
     * 持久化值与 auth_account.role 的受限取值保持一致，避免枚举重命名破坏历史数据。
     */
    @EnumValue
    private final String value;
}
