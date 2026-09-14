package com.calles.platform.content.domain.model;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 内容服务通用的实体可用性基准状态枚举。
 *
 * <p>职责与规范说明：
 * <ul>
 *   <li><b>所属边界</b>：表征全平台统一层面的可用性治理基准（区别于业务流转状态）；</li>
 *   <li><b>状态取值</b>：
 *     <ul>
 *       <li>{@link #ACTIVE}：正常可用；</li>
 *       <li>{@link #DISABLED}：平台级封禁、冻结或强制屏蔽。</li>
 *     </ul>
 *   </li>
 *   <li><b>持久化映射</b>：使用 MyBatis-Plus {@link EnumValue} 注解，在数据库中直接映射为字符串列。</li>
 * </ul>
 * </p>
 */
@Getter
@RequiredArgsConstructor
public enum CommonStatus {

    /** 正常可用状态。 */
    ACTIVE("ACTIVE"),

    /** 平台治理级违规封禁、停用或下线屏蔽状态。 */
    DISABLED("DISABLED");

    /** 数据库列存储与 API 交互对应的字符串字面量值。 */
    @EnumValue
    private final String value;
}
