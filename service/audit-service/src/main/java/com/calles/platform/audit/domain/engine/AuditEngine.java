package com.calles.platform.audit.domain.engine;

import com.calles.platform.audit.domain.model.enums.AuditDimension;

/**
 * 机器安全合规审查引擎顶层统一抽象契约。
 *
 * <p>定义所有自动化审核引擎的基础维度支持、类型元数据及生命周期规范，为文本、图片、视频等各维度引擎提供统一的多态抽象。</p>
 */
public interface AuditEngine {

    /**
     * 获取当前引擎所支持的核心审查维度。
     *
     * @return 审核维度枚举（如 {@link AuditDimension#TEXT}、{@link AuditDimension#IMAGE}、{@link AuditDimension#VIDEO}）
     */
    AuditDimension getDimension();

    /**
     * 获取当前引擎的唯一类型标识名称（如 "LOCAL_DFA"、"RULE_IMAGE"、"RULE_VIDEO"）。
     *
     * @return 引擎标识字符串
     */
    String getEngineType();

    /**
     * 判断当前引擎是否支持指定的审查维度。
     *
     * @param dimension 待判定的审查维度
     * @return 若支持则返回 true，否则返回 false
     */
    default boolean supports(AuditDimension dimension) {
        return dimension != null && getDimension() == dimension;
    }
}
