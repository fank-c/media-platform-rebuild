package com.calles.platform.recommend.domain.model;

/**
 * 视频特征向量提取生命周期状态枚举。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：推荐服务领域模型层；</li>
 *   <li><b>生命周期流转</b>：PROCESSING (处理中) -> COMPLETED (提取完成) / FAILED (提取失败)。</li>
 * </ul>
 * </p>
 */
public enum VectorStatus {

    /**
     * 向量特征正在异步生成与写入中。
     */
    PROCESSING("PROCESSING", "处理中"),

    /**
     * 向量特征已成功生成并完成持久化与同步。
     */
    COMPLETED("COMPLETED", "已完成"),

    /**
     * 向量特征提取或存储异常失败。
     */
    FAILED("FAILED", "失败");

    /** 状态编码字符串。 */
    private final String code;

    /** 状态中文显示描述。 */
    private final String description;

    /**
     * 构造函数。
     *
     * @param code 状态代码
     * @param description 描述
     */
    VectorStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 获取状态代码。
     *
     * @return 状态代码
     */
    public String getCode() {
        return code;
    }

    /**
     * 获取状态描述。
     *
     * @return 中文描述
     */
    public String getDescription() {
        return description;
    }

    /**
     * 根据编码解析枚举。
     *
     * @param code 编码字符串
     * @return 匹配的枚举实例，非法时默认返回 COMPLETED
     */
    public static VectorStatus fromCode(String code) {
        if (code == null) {
            return COMPLETED;
        }
        for (VectorStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return COMPLETED;
    }
}
