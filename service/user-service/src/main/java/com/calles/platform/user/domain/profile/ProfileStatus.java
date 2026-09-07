package com.calles.platform.user.domain.profile;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 用户资料在 user-service 内的可用状态。
 *
 * <p>该状态只约束资料的展示和维护生命周期，不替代 auth-service 对登录、令牌签发与授权的
 * {@code AccountStatus} 判断。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ProfileStatus {

    /** 资料可正常展示和维护。 */
    ACTIVE("ACTIVE"),
    /** 资料被停用，不应作为可用资料对外展示。 */
    DISABLED("DISABLED");

    /**
     * 持久化值与 user_profile.status 的受限取值保持一致，避免枚举重命名破坏历史数据。
     */
    @EnumValue
    private final String value;
}
