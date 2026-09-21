package com.calles.platform.recommend.domain.model;

import lombok.Getter;

/**
 * 推荐候选视频可用生命周期状态枚举。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：推荐候选池（Candidate Pool）准入与调度状态；</li>
 *   <li><b>状态控制</b>：仅处于 ACTIVE 状态的视频允许被召回、排序并下发给终端用户；</li>
 *   <li><b>合规熔断</b>：创作者下线或违规封禁后立即转为非活跃态，彻底剥夺推荐资格。</li>
 * </ul>
 * </p>
 */
@Getter
public enum CandidateStatus {

    /** 正常推荐准入中（已公开发布，可参与召回、排序与打散）。 */
    ACTIVE("ACTIVE", "正常推荐"),

    /** 创作者主动下线（暂停推荐，保留统计元数据）。 */
    OFFLINE("OFFLINE", "已下线"),

    /** 平台合规治理封禁（违规下线，绝对禁止推荐分发）。 */
    BANNED("BANNED", "已封禁");

    /** 状态枚举代码标识。 */
    private final String code;

    /** 状态业务中文说明。 */
    private final String description;

    CandidateStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据字符串字面量解析状态枚举，忽略大小写并自动去除空格。
     *
     * @param code 状态代码字符串
     * @return 对应的 {@link CandidateStatus} 枚举实例，输入为空时返回 null
     * @throws IllegalArgumentException 当传入未知状态字符串时抛出
     */
    public static CandidateStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (CandidateStatus status : values()) {
            if (status.getCode().equalsIgnoreCase(code.trim())) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知候选池状态代码: " + code);
    }
}
