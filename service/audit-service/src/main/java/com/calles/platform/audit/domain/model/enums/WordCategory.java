package com.calles.platform.audit.domain.model.enums;

import lombok.Getter;

/**
 * 敏感词分类枚举。
 */
@Getter
public enum WordCategory {

    /** 涉政涉敏。 */
    POLITICS("POLITICS", "涉政"),

    /** 涉黄低俗。 */
    PORN("PORN", "涉黄"),

    /** 暴力血腥。 */
    VIOLENCE("VIOLENCE", "暴恐"),

    /** 辱骂攻击。 */
    ABUSE("ABUSE", "辱骂"),

    /** 违规广告。 */
    AD("AD", "广告"),

    /** 通用违规。 */
    GENERAL("GENERAL", "通用");

    private final String code;
    private final String description;

    WordCategory(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static WordCategory fromCode(String code) {
        for (WordCategory cat : values()) {
            if (cat.code.equalsIgnoreCase(code)) {
                return cat;
            }
        }
        return GENERAL;
    }
}
