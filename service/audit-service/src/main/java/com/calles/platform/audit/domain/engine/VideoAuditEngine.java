package com.calles.platform.audit.domain.engine;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;

/**
 * 视频多媒体资产合规审查引擎接口规范。
 *
 * <p>继承自 {@link AuditEngine} 顶层契约，支持对音视频流文件资产的存在性及合规性执行审查。</p>
 */
public interface VideoAuditEngine extends AuditEngine {

    @Override
    default AuditDimension getDimension() {
        return AuditDimension.VIDEO;
    }

    /**
     * 对视频资产执行基础合规性审查（通用方法）。
     *
     * @param videoFileId 视频文件资产 ID
     * @return 审查判定明细结果
     */
    EngineAuditResult audit(String videoFileId);

    /**
     * 对主视频资产执行审查（向后兼容旧版调用语义）。
     *
     * @param videoFileId 视频文件资产 ID
     * @return 审查判定明细结果
     */
    default EngineAuditResult auditVideo(String videoFileId) {
        return audit(videoFileId);
    }
}
