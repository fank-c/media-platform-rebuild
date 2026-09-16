package com.calles.platform.audit.interfaces.http.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 审核微服务 HTTP 入参请求契约集合 (AuditRequests)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：接口层对外暴露的入参契约定义，杜绝前端直接组装或依赖领域实体；</li>
 *   <li><b>校验规则</b>：基于 Jakarta Validation 注解在接入层完成非空、格式与取值范围校验；</li>
 *   <li><b>不应承担的工作</b>：不包含任何业务处理逻辑或跨层状态流转。</li>
 * </ul>
 * </p>
 */
public final class AuditRequests {

    private AuditRequests() {
        // 私有构造器，禁止实例化纯契约容器类
    }

    /**
     * 内部模拟提审测试请求体。
     *
     * @param videoId 关联的内部视频全局主键 ID (UUID 32位)
     * @param vid 视频公开业务短码 (如 cv10086)
     * @param authorId 创作者账号全局唯一 ID
     * @param title 视频标题文本快照
     * @param description 视频简介文本快照
     * @param coverFileId 封面图片文件资产 ID
     * @param videoFileId 主视频文件资产 ID
     */
    public record MockSubmit(
            @NotBlank(message = "videoId 不能为空")
            String videoId,

            String vid,

            @NotBlank(message = "authorId 不能为空")
            String authorId,

            String title,

            String description,

            @NotBlank(message = "coverFileId 不能为空")
            String coverFileId,

            @NotBlank(message = "videoFileId 不能为空")
            String videoFileId
    ) {}

    /**
     * 平台管理端人工复审裁决请求体。
     *
     * @param action 审核操作类型：APPROVE（人工通过）或 REJECT（人工驳回）
     * @param reason 审核判定原因说明（驳回时必填，通过时可选）
     */
    public record ManualReview(
            @NotBlank(message = "审核操作 action 不能为空")
            @Pattern(regexp = "^(APPROVE|REJECT)$", message = "action 仅支持 APPROVE 或 REJECT")
            String action,

            String reason
    ) {}

    /**
     * 平台管理端审核工单多条件检索过滤请求参数。
     *
     * @param stage 审核生命周期阶段 (RECEIVED, MACHINE_AUDITING, MANUAL_PENDING, FINISHED)
     * @param result 审核结果 (PENDING, PASSED, REJECTED)
     * @param reviewLevel 风险级别 (NORMAL, SUSPICIOUS, ILLEGAL)
     * @param bizId 关联的业务主键 ID (如 videoId)
     * @param page 分页页码 (从 1 起始)
     * @param size 每页大小 (1-100)
     */
    public record TaskQuery(
            String stage,
            String result,
            String reviewLevel,
            String bizId,
            Integer page,
            Integer size
    ) {
        /**
         * 获取安全的规范化页码，默认第一页。
         *
         * @return 合法页码
         */
        public int getPage() {
            return (page != null && page > 0) ? page : 1;
        }

        /**
         * 获取安全的规范化每页大小，默认 20，上限 100。
         *
         * @return 合法分页大小
         */
        public int getSize() {
            if (size == null || size <= 0) {
                return 20;
            }
            return Math.min(size, 100);
        }
    }
}
