package com.calles.platform.audit.domain.engine;

import com.calles.platform.audit.domain.engine.model.EngineAuditResult;
import com.calles.platform.audit.domain.model.enums.AuditDimension;

/**
 * 图像/封面多媒体合规审查引擎接口规范。
 *
 * <p>继承自 {@link AuditEngine} 顶层契约，支持对视频封面、用户头像或内容插图等多媒体图像资产执行安全合规审查。</p>
 */
public interface ImageAuditEngine extends AuditEngine {

    @Override
    default AuditDimension getDimension() {
        return AuditDimension.IMAGE;
    }

    /**
     * 对图像资产执行安全合规审查（通用方法）。
     *
     * @param imageFileId 图像文件资产 ID
     * @return 审查判定明细结果
     */
    EngineAuditResult audit(String imageFileId);

    /**
     * 对封面图片执行安全合规审查（向后兼容旧版调用语义）。
     *
     * @param coverFileId 封面文件资产 ID
     * @return 审查判定明细结果
     */
    default EngineAuditResult auditCover(String coverFileId) {
        return audit(coverFileId);
    }

    /**
     * 对封面图片执行安全合规审查（支持透传创作者与任务上下文）。
     *
     * @param coverFileId 封面文件资产 ID
     * @param authorId 创作者唯一标识
     * @param taskId 关联审核任务全局唯一 ID
     * @return 审查判定明细结果
     */
    default EngineAuditResult auditCover(String coverFileId, String authorId, String taskId) {
        return auditCover(coverFileId);
    }
}
