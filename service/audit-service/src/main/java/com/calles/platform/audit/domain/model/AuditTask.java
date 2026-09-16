package com.calles.platform.audit.domain.model;

import com.calles.platform.audit.domain.model.enums.AuditResult;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.enums.CallbackStatus;
import com.calles.platform.audit.domain.model.enums.ReviewLevel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 审核任务聚合根 (AuditTask)。
 *
 * <p>职责与设计原则：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务核心领域聚合根，完全拥有单次提审全生命周期的数据一致性；</li>
 *   <li><b>状态机流转</b>：管理从提审受理 (RECEIVED) -> 机审中 (MACHINE_AUDITING) -> 人工待审 (MANUAL_PENDING) -> 终审完成 (FINISHED)；</li>
 *   <li><b>终态保护</b>：一旦进入 FINISHED，除回调状态重试外，禁止非法逆向跃迁回机审中；</li>
 *   <li><b>不应承担的工作</b>：不直接参与底层数据库 SQL 组装，不直接发送 HTTP/MQ 外部请求。</li>
 * </ul>
 * </p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditTask {

    /** 任务内部物理主键 (UUID 32位无短横线)。 */
    private String id;

    /** 业务可读唯一流水号 (如 aud_1789476873_5ff5d633)。 */
    private String taskNo;

    /** 业务归属类型 (首期固定为 VIDEO)。 */
    private String bizType;

    /** 业务内部主键 ID (如关联 video_content.id)。 */
    private String bizId;

    /** 业务公开短码 (如 cv10086)。 */
    private String bizVid;

    /** 提审创作者账号 ID。 */
    private String authorId;

    /** 提审时的标题快照。 */
    private String titleSnapshot;

    /** 提审时的简介快照。 */
    private String descriptionSnapshot;

    /** 封面图片文件资产 ID。 */
    private String coverFileId;

    /** 主视频文件资产 ID。 */
    private String videoFileId;

    /** 审核生命周期阶段 (RECEIVED, MACHINE_AUDITING, MANUAL_PENDING, FINISHED)。 */
    private AuditStage stage;

    /** 最终审核裁决结论 (PENDING, PASSED, REJECTED)。 */
    private AuditResult result;

    /** 审核打回/驳回原因说明。 */
    private String rejectReason;

    /** 综合研判风险级别 (NORMAL, SUSPICIOUS, ILLEGAL)。 */
    private ReviewLevel reviewLevel;

    /** 终审操作人标识 (SYSTEM 或管理员 ID)。 */
    private String operatorId;

    /** 回调内容微服务状态 (PENDING, SUCCESS, FAILED)。 */
    private CallbackStatus callbackStatus;

    /** 回调已重试次数。 */
    private int callbackRetries;

    /** 创建时间戳。 */
    private Instant createdAt;

    /** 更新时间戳。 */
    private Instant updatedAt;

    /**
     * 工厂方法：基于提审载荷创建初始受理任务聚合根。
     *
     * <p>初始状态：阶段为 {@link AuditStage#RECEIVED}，结果为 {@link AuditResult#PENDING}，回调状态为 {@link CallbackStatus#PENDING}。</p>
     *
     * @param bizId 业务主键（视频 ID）
     * @param bizVid 业务公开短码
     * @param authorId 作者账号 ID
     * @param title 标题快照
     * @param description 简介快照
     * @param coverFileId 封面文件 ID
     * @param videoFileId 视频文件 ID
     * @return 初始化的审核任务实例
     */
    /**
     * 工厂方法：基于通用业务提审参数创建初始受理任务聚合根。
     *
     * <p>初始状态：阶段为 {@link AuditStage#RECEIVED}，结果为 {@link AuditResult#PENDING}，回调状态为 {@link CallbackStatus#PENDING}。</p>
     *
     * @param bizType 业务归属类型（如 VIDEO 等）
     * @param bizId 业务主键
     * @param bizVid 业务公开短码
     * @param authorId 作者账号 ID
     * @param title 标题快照
     * @param description 简介快照
     * @param coverFileId 封面文件 ID
     * @param videoFileId 视频文件 ID
     * @return 初始化的审核任务实例
     */
    public static AuditTask createTask(
            String bizType,
            String bizId,
            String bizVid,
            String authorId,
            String title,
            String description,
            String coverFileId,
            String videoFileId
    ) {
        // 步骤 1：生成 UUID 主键与业务流水号
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String taskNo = "aud_" + System.currentTimeMillis() + "_" + uuid.substring(0, 8);
        Instant now = Instant.now();

        // 步骤 2：组装初始聚合根实体
        return AuditTask.builder()
                .id(uuid)
                .taskNo(taskNo)
                .bizType(bizType != null ? bizType.toUpperCase() : "VIDEO")
                .bizId(bizId)
                .bizVid(bizVid)
                .authorId(authorId)
                .titleSnapshot(title != null ? title : "")
                .descriptionSnapshot(description)
                .coverFileId(coverFileId)
                .videoFileId(videoFileId)
                .stage(AuditStage.RECEIVED)
                .result(AuditResult.PENDING)
                .rejectReason(null)
                .reviewLevel(ReviewLevel.NORMAL)
                .operatorId("SYSTEM")
                .callbackStatus(CallbackStatus.PENDING)
                .callbackRetries(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 工厂方法：基于视频提审载荷创建初始受理任务聚合根。
     *
     * @param bizId 业务主键（视频 ID）
     * @param bizVid 业务公开短码
     * @param authorId 作者账号 ID
     * @param title 标题快照
     * @param description 简介快照
     * @param coverFileId 封面文件 ID
     * @param videoFileId 视频文件 ID
     * @return 初始化的审核任务实例
     */
    public static AuditTask createVideoAuditTask(
            String bizId,
            String bizVid,
            String authorId,
            String title,
            String description,
            String coverFileId,
            String videoFileId
    ) {
        return createTask("VIDEO", bizId, bizVid, authorId, title, description, coverFileId, videoFileId);
    }

    /**
     * 启动自动化机审流水线，将任务阶段跃迁至 MACHINE_AUDITING。
     *
     * @throws IllegalStateException 若当前阶段不是 RECEIVED
     */
    public void startMachineAudit() {
        // 步骤 1：前置状态机校验，仅刚受理状态允许启动机审
        if (this.stage != AuditStage.RECEIVED) {
            throw new IllegalStateException("仅在 RECEIVED 状态下可启动机审，当前状态: " + this.stage);
        }

        // 步骤 2：流转为 MACHINE_AUDITING 并刷新更新时间
        this.stage = AuditStage.MACHINE_AUDITING;
        this.updatedAt = Instant.now();
    }

    /**
     * 机审自动化裁决完成，根据综合风险等级驱动状态跃迁。
     *
     * <p><b>状态机流转规则</b>：
     * <ul>
     *   <li>{@link ReviewLevel#ILLEGAL}（严重违规）：直接终结 FINISHED，结论 REJECTED；</li>
     *   <li>{@link ReviewLevel#SUSPICIOUS}（疑似可疑）：挂起为 MANUAL_PENDING 进入人工工单池，结论保持 PENDING；</li>
     *   <li>{@link ReviewLevel#NORMAL}（合规正常）：直接终结 FINISHED，结论 PASSED。</li>
     * </ul>
     * </p>
     *
     * @param level 综合研判风险级别
     * @param reason 驳回或疑点原因摘要
     * @throws IllegalStateException 若当前阶段不是 MACHINE_AUDITING
     */
    public void completeMachineAudit(ReviewLevel level, String reason) {
        // 步骤 1：状态合法性前置检查
        if (this.stage != AuditStage.MACHINE_AUDITING) {
            throw new IllegalStateException("仅在 MACHINE_AUDITING 状态下可完成机审，当前状态: " + this.stage);
        }

        // 步骤 2：记录风险等级与操作人
        this.reviewLevel = level;
        this.operatorId = "SYSTEM";
        this.updatedAt = Instant.now();

        // 步骤 3：依据研判级别执行分流
        switch (level) {
            case ILLEGAL -> {
                // 严重违规：直接自动驳回并完结终审
                this.stage = AuditStage.FINISHED;
                this.result = AuditResult.REJECTED;
                this.rejectReason = (reason != null && !reason.isBlank()) ? reason : "内容包含严重违规信息，机审驳回";
            }
            case SUSPICIOUS -> {
                // 疑似可疑：挂起流转为人工待审工单池
                this.stage = AuditStage.MANUAL_PENDING;
                this.result = AuditResult.PENDING;
                this.rejectReason = reason;
            }
            case NORMAL -> {
                // 合规正常：直接自动放行并完结终审
                this.stage = AuditStage.FINISHED;
                this.result = AuditResult.PASSED;
                this.rejectReason = null;
            }
        }
    }

    /**
     * 异步机审结果回调裁决完成，驱动状态机从机审中或待复审中间态跃迁至终局。
     *
     * <p><b>幂等性保障</b>：若任务已处于 {@link AuditStage#FINISHED}（例如已被人工先行处理或历史回调已消费），
     * 保持既有结论不变，具备幂等无害性。</p>
     *
     * @param level 综合研判风险级别
     * @param reason 驳回或疑点原因摘要
     * @param operator 操作者标识（如 ALIYUN_CALLBACK）
     */
    public void completeAsyncMachineAudit(ReviewLevel level, String reason, String operator) {
        // 步骤 1：幂等守卫：若任务已产生终局归档，直接忽略本次异步回调流转
        if (this.stage == AuditStage.FINISHED) {
            return;
        }

        // 步骤 2：仅允许从 MACHINE_AUDITING 或 MANUAL_PENDING 中间态跃迁
        if (this.stage != AuditStage.MACHINE_AUDITING && this.stage != AuditStage.MANUAL_PENDING) {
            throw new IllegalStateException("当前状态不支持异步机审回调裁决: " + this.stage);
        }

        // 步骤 3：更新风险等级与操作人
        this.reviewLevel = level;
        this.operatorId = (operator != null && !operator.isBlank()) ? operator : "ALIYUN_CALLBACK";
        this.updatedAt = Instant.now();

        // 步骤 4：根据综合风险级别执行状态流转
        switch (level) {
            case ILLEGAL -> {
                this.stage = AuditStage.FINISHED;
                this.result = AuditResult.REJECTED;
                this.rejectReason = (reason != null && !reason.isBlank()) ? reason : "内容包含严重违规信息，机审驳回";
            }
            case SUSPICIOUS -> {
                this.stage = AuditStage.MANUAL_PENDING;
                this.result = AuditResult.PENDING;
                this.rejectReason = reason;
            }
            case NORMAL -> {
                this.stage = AuditStage.FINISHED;
                this.result = AuditResult.PASSED;
                this.rejectReason = null;
            }
        }
    }

    /**
     * 管理员人工审核判定：审批通过。
     *
     * <p><b>状态机跃迁</b>：阶段由 MANUAL_PENDING -> FINISHED，结论设为 PASSED，记录操作人。</p>
     *
     * @param adminId 审核管理员 ID
     * @throws IllegalStateException 若当前阶段不是 MANUAL_PENDING
     */
    public void approveByManual(String adminId) {
        // 步骤 1：仅处于人工待复审状态的工单允许审批
        if (this.stage != AuditStage.MANUAL_PENDING) {
            throw new IllegalStateException("仅处于 MANUAL_PENDING 状态的工单可人工审批通过，当前状态: " + this.stage);
        }

        // 步骤 2：流转为 FINISHED / PASSED
        this.stage = AuditStage.FINISHED;
        this.result = AuditResult.PASSED;
        this.rejectReason = null;
        this.operatorId = adminId;
        this.updatedAt = Instant.now();
    }

    /**
     * 管理员人工审核判定：审批驳回。
     *
     * <p><b>状态机跃迁</b>：阶段由 MANUAL_PENDING -> FINISHED，结论设为 REJECTED，记录操作人与驳回原因。</p>
     *
     * @param adminId 审核管理员 ID
     * @param reason 驳回原因说明
     * @throws IllegalStateException 若当前阶段不是 MANUAL_PENDING
     */
    public void rejectByManual(String adminId, String reason) {
        // 步骤 1：仅处于人工待复审状态的工单允许驳回
        if (this.stage != AuditStage.MANUAL_PENDING) {
            throw new IllegalStateException("仅处于 MANUAL_PENDING 状态的工单可人工审批驳回，当前状态: " + this.stage);
        }

        // 步骤 2：流转为 FINISHED / REJECTED 并留存驳回说明
        this.stage = AuditStage.FINISHED;
        this.result = AuditResult.REJECTED;
        this.rejectReason = (reason != null && !reason.isBlank()) ? reason : "经人工复审判定违规驳回";
        this.operatorId = adminId;
        this.updatedAt = Instant.now();
    }

    /**
     * 标记回调下游内容服务成功，流转 callbackStatus 为 SUCCESS。
     */
    public void markCallbackSuccess() {
        this.callbackStatus = CallbackStatus.SUCCESS;
        this.updatedAt = Instant.now();
    }

    /**
     * 标记回调下游内容服务失败，流转 callbackStatus 为 FAILED 并递增重试计数。
     */
    public void markCallbackFailed() {
        this.callbackStatus = CallbackStatus.FAILED;
        this.callbackRetries++;
        this.updatedAt = Instant.now();
    }
}
