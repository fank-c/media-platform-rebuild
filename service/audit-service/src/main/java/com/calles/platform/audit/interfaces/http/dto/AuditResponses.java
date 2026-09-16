package com.calles.platform.audit.interfaces.http.dto;

import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.AuditTask;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 审核微服务 HTTP 出参响应契约集合 (AuditResponses)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：接口层响应数据载体 (DTO)，向调用方屏蔽领域模型聚合根实现细节；</li>
 *   <li><b>防腐隔离</b>：负责将 {@link AuditTask} 与 {@link AuditDetail} 转化为面向前端或内部客户端的安全视图。</li>
 * </ul>
 * </p>
 */
public final class AuditResponses {

    private AuditResponses() {
        // 私有构造器，禁止实例化纯契约容器类
    }

    /**
     * 单项审查维度证据明细响应项。
     *
     * @param id 明细主键 ID
     * @param dimension 审查维度 (TEXT, IMAGE, VIDEO)
     * @param engineType 判审引擎类型 (如 LOCAL_DFA, RULE_IMAGE)
     * @param level 风险判定级别 (NORMAL, SUSPICIOUS, ILLEGAL)
     * @param confidence 判定置信度分值 (0.00 - 100.00)
     * @param hitWords 命中的违规词条或特征标签
     * @param detailLog 引擎原始判定明细日志
     * @param createdAt 创建时间戳
     */
    public record DetailItem(
            String id,
            String dimension,
            String engineType,
            String level,
            BigDecimal confidence,
            String hitWords,
            String detailLog,
            Instant createdAt
    ) {
        /**
         * 从领域实体映射构建。
         *
         * @param detail 领域明细实体
         * @return 响应项
         */
        public static DetailItem fromDomain(AuditDetail detail) {
            if (detail == null) {
                return null;
            }
            return new DetailItem(
                    detail.getId(),
                    detail.getDimension() != null ? detail.getDimension().name() : null,
                    detail.getEngineType(),
                    detail.getLevel() != null ? detail.getLevel().name() : null,
                    detail.getConfidence(),
                    detail.getHitWords(),
                    detail.getDetailLog(),
                    detail.getCreatedAt()
            );
        }
    }

    /**
     * 审核任务概览列表项。
     *
     * @param id 任务主键 ID
     * @param taskNo 业务流水编号 (aud_xxx)
     * @param bizType 业务类型 (VIDEO)
     * @param bizId 业务主键 ID (videoId)
     * @param bizVid 业务公开短码 (cv...)
     * @param authorId 创作者账号 ID
     * @param titleSnapshot 视频标题快照
     * @param stage 审核生命周期阶段 (RECEIVED, MACHINE_AUDITING, MANUAL_PENDING, FINISHED)
     * @param result 审核结果 (PENDING, PASSED, REJECTED)
     * @param reviewLevel 风险级别 (NORMAL, SUSPICIOUS, ILLEGAL)
     * @param rejectReason 驳回原因
     * @param operatorId 终审操作人 (SYSTEM 或管理员账号)
     * @param callbackStatus 回调状态 (PENDING, SUCCESS, FAILED)
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public record TaskItem(
            String id,
            String taskNo,
            String bizType,
            String bizId,
            String bizVid,
            String authorId,
            String titleSnapshot,
            String stage,
            String result,
            String reviewLevel,
            String rejectReason,
            String operatorId,
            String callbackStatus,
            Instant createdAt,
            Instant updatedAt
    ) {
        /**
         * 从领域聚合根映射构建列表项。
         *
         * @param task 审核任务领域实体
         * @return 响应项
         */
        public static TaskItem fromDomain(AuditTask task) {
            if (task == null) {
                return null;
            }
            return new TaskItem(
                    task.getId(),
                    task.getTaskNo(),
                    task.getBizType(),
                    task.getBizId(),
                    task.getBizVid(),
                    task.getAuthorId(),
                    task.getTitleSnapshot(),
                    task.getStage() != null ? task.getStage().name() : null,
                    task.getResult() != null ? task.getResult().name() : null,
                    task.getReviewLevel() != null ? task.getReviewLevel().name() : null,
                    task.getRejectReason(),
                    task.getOperatorId(),
                    task.getCallbackStatus() != null ? task.getCallbackStatus().name() : null,
                    task.getCreatedAt(),
                    task.getUpdatedAt()
            );
        }
    }

    /**
     * 审核任务全景详情响应（含主任务信息与多维度机审判定证据明细列表）。
     *
     * @param task 主任务摘要信息
     * @param coverFileId 封面文件资产 ID
     * @param videoFileId 视频文件资产 ID
     * @param descriptionSnapshot 简介文本快照
     * @param details 多维度证据明细列表
     */
    public record TaskDetail(
            TaskItem task,
            String coverFileId,
            String videoFileId,
            String descriptionSnapshot,
            List<DetailItem> details
    ) {
        /**
         * 聚合任务与明细实体构建全景详情。
         *
         * @param task 领域任务
         * @param details 领域明细列表
         * @return 全景详情 DTO
         */
        public static TaskDetail of(AuditTask task, List<AuditDetail> details) {
            TaskItem taskItem = TaskItem.fromDomain(task);
            List<DetailItem> detailItems = (details != null)
                    ? details.stream().map(DetailItem::fromDomain).toList()
                    : Collections.emptyList();
            return new TaskDetail(
                    taskItem,
                    task != null ? task.getCoverFileId() : null,
                    task != null ? task.getVideoFileId() : null,
                    task != null ? task.getDescriptionSnapshot() : null,
                    detailItems
            );
        }
    }

    /**
     * 管理端审核工单分页列表响应。
     *
     * @param records 当前页的工单记录列表
     * @param total 符合条件的全部记录总数
     * @param page 当前页码
     * @param size 每页大小
     */
    public record TaskPage(
            List<TaskItem> records,
            long total,
            long page,
            long size
    ) {}
}
