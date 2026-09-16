package com.calles.platform.audit.application.executor.model;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;
import lombok.Builder;

import java.util.Collections;
import java.util.Map;

/**
 * 审核执行上下文模型。
 *
 * <p>封装业务提审的核心元数据、文件凭证及历史免审复用判定结果，供各业务专属执行器消费。</p>
 */
@Builder(toBuilder = true)
public record AuditContext(
        String taskId,
        AuditBizType bizType,
        String bizId,
        String bizVid,
        String authorId,
        String title,
        String description,
        String coverFileId,
        String videoFileId,
        Map<AuditDimension, EngineAuditResult> reusableResults
) {
    /**
     * 获取业务类型的字符串编码，若为空则返回 null。
     */
    public String bizTypeCode() {
        return bizType != null ? bizType.getCode() : null;
    }

    /**
     * 获取可复用的历史审核判定映射（安全防御保证非 null）。
     */
    @Override
    public Map<AuditDimension, EngineAuditResult> reusableResults() {
        return reusableResults != null ? reusableResults : Collections.emptyMap();
    }

    /**
     * 构建视频审核上下文辅助工厂方法。
     */
    public static AuditContext forVideo(
            String taskId,
            String videoId,
            String vid,
            String authorId,
            String title,
            String description,
            String coverFileId,
            String videoFileId
    ) {
        return forVideo(taskId, videoId, vid, authorId, title, description, coverFileId, videoFileId, null);
    }

    /**
     * 构建包含历史免审判定映射的视频审核上下文辅助工厂方法。
     */
    public static AuditContext forVideo(
            String taskId,
            String videoId,
            String vid,
            String authorId,
            String title,
            String description,
            String coverFileId,
            String videoFileId,
            Map<AuditDimension, EngineAuditResult> reusableResults
    ) {
        return AuditContext.builder()
                .taskId(taskId)
                .bizType(AuditBizType.VIDEO)
                .bizId(videoId)
                .bizVid(vid)
                .authorId(authorId)
                .title(title)
                .description(description)
                .coverFileId(coverFileId)
                .videoFileId(videoFileId)
                .reusableResults(reusableResults)
                .build();
    }
}
