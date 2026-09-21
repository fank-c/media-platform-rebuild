package com.calles.platform.recommend.domain.model.block;

import lombok.Getter;

/**
 * 用户明确屏蔽约束类型枚举。
 *
 * <p>用于区分用户主动拉黑的具体对象维度，作为推荐召回后的最高优先级一票否决门禁。</p>
 */
@Getter
public enum BlockType {

    /** 屏蔽特定单条视频 (targetId 对应 vid)。 */
    VIDEO("VIDEO", "视频"),

    /** 屏蔽特定创作者 (targetId 对应 authorId)。 */
    AUTHOR("AUTHOR", "创作者"),

    /** 屏蔽特定主题标签 (targetId 对应 tagId)。 */
    TOPIC("TOPIC", "主题标签");

    /** 类型编码。 */
    private final String code;

    /** 业务描述。 */
    private final String description;

    BlockType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据编码安全解析枚举实例。
     *
     * @param code 编码字符串
     * @return 对应的枚举实例，未找到时抛出异常
     */
    public static BlockType fromCode(String code) {
        for (BlockType type : values()) {
            if (type.getCode().equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的屏蔽类型编码: " + code);
    }
}
