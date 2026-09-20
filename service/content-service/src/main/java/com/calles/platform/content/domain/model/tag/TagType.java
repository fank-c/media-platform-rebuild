package com.calles.platform.content.domain.model.tag;

import lombok.Getter;

/**
 * 标签类型枚举 (TagType)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：内容领域标签类型的核心定义；</li>
 *   <li><b>业务用途</b>：区分泛化领域标签（用于宏观分类、频道筛选、推荐占比控制与多样性打散）与具体主题标签（用于精细排序、偏好加分与推荐解释）；</li>
 *   <li><b>演进约束</b>：单层扁平标签结构，通过枚举区分属性，不强行引入深层树形层级。</li>
 * </ul>
 * </p>
 */
@Getter
public enum TagType {

    /** 泛化领域标签（如：编程、摄影、烹饪、数码、音乐等）。 */
    DOMAIN("DOMAIN", "泛化领域标签"),

    /** 具体主题标签（如：Java、Spring Boot、夜景摄影、川菜等）。 */
    TOPIC("TOPIC", "具体主题标签");

    /** 类型唯一代码标识。 */
    private final String code;

    /** 类型中文业务描述。 */
    private final String description;

    /**
     * 枚举构造方法。
     *
     * @param code 类型编码字面量
     * @param description 业务描述信息
     */
    TagType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据字符串编码解析标签类型枚举，忽略大小写并自动去除首尾空白。
     *
     * @param code 标签类型编码
     * @return 对应的 {@link TagType} 枚举实例，输入为空时返回 null
     * @throws IllegalArgumentException 当传入未知类型字符串时抛出
     */
    public static TagType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (TagType type : values()) {
            if (type.getCode().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知标签类型: " + code);
    }
}
