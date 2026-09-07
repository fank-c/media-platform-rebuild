package com.calles.platform.auth.domain.account;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 认证账户的可用状态；禁用账户不得再获得新的认证凭据。
 */
@Getter
@RequiredArgsConstructor
public enum AccountStatus {

    /** 可正常认证并签发凭据。 */
    ACTIVE("ACTIVE"),
    /** 禁止登录和刷新；已有 Access Token 的即时失效由注销或网关策略负责。 */
    DISABLED("DISABLED");

    /**
     * 持久化值与 auth_account.status 的受限取值保持一致，避免枚举重命名破坏历史数据。
     */
    @EnumValue
    private final String value;
}
