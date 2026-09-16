package com.calles.platform.audit.domain.engine;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;

/**
 * 图像/封面多媒体合规审查引擎接口规范。
 */
public interface ImageAuditEngine {

    /**
     * 对封面图片执行安全合规审查。
     *
     * @param coverFileId 封面文件资产 ID
     * @return 审查判定明细结果
     */
    EngineAuditResult auditCover(String coverFileId);
}
