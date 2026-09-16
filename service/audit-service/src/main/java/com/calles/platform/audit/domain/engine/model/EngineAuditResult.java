package com.calles.platform.audit.domain.engine.model;

import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 单项审核引擎执行判定的结果值对象模型。
 *
 * <p>封装各检测引擎针对文本、图片或视频资产的命中明细、置信度及日志证据。</p>
 */
@Builder
public record EngineAuditResult(
        AuditDimension dimension,
        String engineType,
        ReviewLevel level,
        BigDecimal confidence,
        List<String> hitWords,
        String detailLog
) {
    public static EngineAuditResult normal(AuditDimension dimension, String engineType, String log) {
        return EngineAuditResult.builder()
                .dimension(dimension)
                .engineType(engineType)
                .level(ReviewLevel.NORMAL)
                .confidence(BigDecimal.valueOf(100.00))
                .hitWords(Collections.emptyList())
                .detailLog(log)
                .build();
    }

    public static EngineAuditResult of(
            AuditDimension dimension,
            String engineType,
            ReviewLevel level,
            BigDecimal confidence,
            List<String> hitWords,
            String log
    ) {
        return EngineAuditResult.builder()
                .dimension(dimension)
                .engineType(engineType)
                .level(level)
                .confidence(confidence != null ? confidence : BigDecimal.valueOf(100.00))
                .hitWords(hitWords != null ? hitWords : Collections.emptyList())
                .detailLog(log)
                .build();
    }

    /**
     * 将引擎计算结果转换为可持久化的审核明细实体。
     *
     * @param taskId 关联的任务主键 ID
     * @return 审核明细实体
     */
    public AuditDetail toAuditDetail(String taskId) {
        String hitWordsStr = (hitWords == null || hitWords.isEmpty()) ? null : String.join(",", hitWords);
        return AuditDetail.of(
                taskId,
                dimension,
                engineType,
                level,
                confidence,
                hitWordsStr,
                detailLog
        );
    }

    /**
     * 从历史持久化审核明细还原为引擎结果（用于重提增量免审复用）。
     *
     * @param detail 历史审核明细
     * @param prefixLog 前缀备注说明
     * @return 引擎审核结果值对象
     */
    public static EngineAuditResult fromAuditDetail(AuditDetail detail, String prefixLog) {
        List<String> hits = (detail.getHitWords() != null && !detail.getHitWords().isBlank())
                ? List.of(detail.getHitWords().split(","))
                : Collections.emptyList();
        String log = (prefixLog != null ? prefixLog : "") + (detail.getDetailLog() != null ? detail.getDetailLog() : "");
        return EngineAuditResult.builder()
                .dimension(detail.getDimension())
                .engineType(detail.getEngineType())
                .level(detail.getLevel())
                .confidence(detail.getConfidence())
                .hitWords(hits)
                .detailLog(log)
                .build();
    }
}
