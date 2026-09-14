package com.calles.platform.content.domain.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 内容服务通用的实体可用性基准状态。
 *
 * <p>对齐全平台标准：ACTIVE 表示正常可用，DISABLED 表示违规封禁或下线屏蔽。</p>
 */
@Getter
@RequiredArgsConstructor
public enum CommonStatus {

    /** 正常可用。 */
    ACTIVE("ACTIVE"),
    /** 违规封禁/停用/屏蔽。 */
    DISABLED("DISABLED");

    @EnumValue
    private final String value;
}
