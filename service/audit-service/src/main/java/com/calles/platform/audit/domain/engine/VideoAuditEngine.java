package com.calles.platform.audit.domain.engine;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;

/**
 * 视频多媒体资产合规审查引擎接口规范。
 */
public interface VideoAuditEngine {

    /**
     * 对视频资产执行基础合规性审查。
     *
     * @param videoFileId 视频文件资产 ID
     * @return 审查判定明细结果
     */
    EngineAuditResult auditVideo(String videoFileId);
}
