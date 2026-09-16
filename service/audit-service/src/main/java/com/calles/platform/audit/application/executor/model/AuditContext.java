package com.calles.platform.audit.application.executor.model;

import lombok.Builder;

/**
 * 审核执行上下文模型。
 *
 * <p>封装业务提审的核心元数据、文件凭证及业务上下文参数，供各业务专属执行器消费。</p>
 */
@Builder
public record AuditContext(
        String taskId,
        String bizType,
        String bizId,
        String bizVid,
        String authorId,
        String title,
        String description,
        String coverFileId,
        String videoFileId
) {
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
        return AuditContext.builder()
                .taskId(taskId)
                .bizType("VIDEO")
                .bizId(videoId)
                .bizVid(vid)
                .authorId(authorId)
                .title(title)
                .description(description)
                .coverFileId(coverFileId)
                .videoFileId(videoFileId)
                .build();
    }
}
