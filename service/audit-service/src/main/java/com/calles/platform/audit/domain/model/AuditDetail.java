package com.calles.platform.audit.domain.model;

import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 审核多维度判定证据明细实体 (AuditDetail)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：依附于 {@link AuditTask} 的单维度判定证据实体；</li>
 *   <li><b>主要职责</b>：留存文本敏感词过滤、封面图片机器识别、视频流合规扫描的具体引擎名称、风险等级、置信度分值与原始日志证据；</li>
 *   <li><b>不可变追溯</b>：明细记录一经生成即作为合规风控审计底账，禁止随意篡改。</li>
 * </ul>
 * </p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditDetail {

    /** 明细主键 ID (UUID 32位无短横线)。 */
    private String id;

    /** 所属审核任务主键 ID (外键关联 audit_task.id)。 */
    private String taskId;

    /** 审查维度 (TEXT: 文本, IMAGE: 封面图片, VIDEO: 视频流资产)。 */
    private AuditDimension dimension;

    /** 判审引擎类型 (如 LOCAL_DFA, RULE_IMAGE, RULE_VIDEO, ALIYUN_GREEN, MANUAL)。 */
    private String engineType;

    /** 该维度的风险级别判定 (NORMAL, SUSPICIOUS, ILLEGAL)。 */
    private ReviewLevel level;

    /** 判定置信度分值 (0.00 - 100.00)，默认 100.00。 */
    private BigDecimal confidence;

    /** 命中的违规词条、安全规则或特征标签快照 (多个以逗号分隔)。 */
    private String hitWords;

    /** 引擎原始输出详情、排查摘要或上下文线索日志。 */
    private String detailLog;

    /** 记录创建时间戳。 */
    private Instant createdAt;

    /**
     * 工厂方法：构建一条审核判定明细实体。
     *
     * @param taskId 关联的任务主键 ID
     * @param dimension 审查维度
     * @param engineType 判审引擎
     * @param level 风险级别
     * @param confidence 置信度分值
     * @param hitWords 命中标签或敏感词文本
     * @param detailLog 判定明细与日志
     * @return 审核明细实例
     */
    public static AuditDetail of(
            String taskId,
            AuditDimension dimension,
            String engineType,
            ReviewLevel level,
            BigDecimal confidence,
            String hitWords,
            String detailLog
    ) {
        // 步骤 1：生成 UUID 主键并赋初值
        return AuditDetail.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .taskId(taskId)
                .dimension(dimension)
                .engineType(engineType)
                .level(level)
                .confidence(confidence != null ? confidence : BigDecimal.valueOf(100.00))
                .hitWords(hitWords)
                .detailLog(detailLog)
                .createdAt(Instant.now())
                .build();
    }
}
