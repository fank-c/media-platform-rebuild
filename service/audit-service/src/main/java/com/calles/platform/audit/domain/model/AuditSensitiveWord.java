package com.calles.platform.audit.domain.model;

import com.calles.platform.audit.domain.model.enums.CommonStatus;
import com.calles.platform.audit.domain.model.enums.WordCategory;
import com.calles.platform.audit.domain.model.enums.WordLevel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 敏感词与合规规则词库实体 (AuditSensitiveWord)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：文本机审词库字典配置实体；</li>
 *   <li><b>主要职责</b>：维护敏感词词条字面量、归属违规类别 (涉暴/涉政/低俗/广告等)、拦截处置等级 (ILLEGAL/SUSPICIOUS) 及启用禁用状态；</li>
 *   <li><b>引擎加载</b>：作为 {@link com.calles.platform.audit.domain.engine.DfaTextAuditEngine} 启动和动态热重载的数据源。</li>
 * </ul>
 * </p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditSensitiveWord {

    /** 主键 ID (UUID 32位无短横线)。 */
    private String id;

    /** 敏感词词条文本字面量。 */
    private String word;

    /** 敏感词违规分类 (GENERAL, POLITICS, PORN, VIOLENCE, CONTRABAND, AD)。 */
    private WordCategory category;

    /** 拦截处置级别 (ILLEGAL: 严重违规直接驳回, SUSPICIOUS: 疑似转人审)。 */
    private WordLevel level;

    /** 可用生命周期状态 (ACTIVE: 启用中, DISABLED: 已停用)。 */
    private CommonStatus status;

    /** 记录创建时间戳。 */
    private Instant createdAt;

    /** 记录最后修改时间戳。 */
    private Instant updatedAt;

    /**
     * 工厂方法：构建初始处于 ACTIVE 启用状态的敏感词实体。
     *
     * @param word 敏感词文本
     * @param category 违规类型枚举
     * @param level 处置等级枚举
     * @return 敏感词领域实体
     */
    public static AuditSensitiveWord of(String word, WordCategory category, WordLevel level) {
        // 步骤 1：记录当前时间
        Instant now = Instant.now();

        // 步骤 2：自动去除首尾空白并装配实体
        return AuditSensitiveWord.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .word(word.trim())
                .category(category != null ? category : WordCategory.GENERAL)
                .level(level != null ? level : WordLevel.ILLEGAL)
                .status(CommonStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
