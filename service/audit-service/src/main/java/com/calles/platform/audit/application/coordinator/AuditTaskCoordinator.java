package com.calles.platform.audit.application.coordinator;

import com.calles.platform.audit.application.executor.model.AuditContext;
import com.calles.platform.audit.application.executor.model.AuditExecutionResult;
import com.calles.platform.audit.application.executor.AuditExecutor;
import com.calles.platform.audit.application.executor.AuditExecutorRouter;
import com.calles.platform.audit.application.service.AuditCallbackService;
import com.calles.platform.audit.domain.model.AuditDetail;
import com.calles.platform.audit.domain.model.enums.AuditStage;
import com.calles.platform.audit.domain.model.AuditTask;
import com.calles.platform.audit.domain.repository.AuditDetailRepository;
import com.calles.platform.audit.domain.repository.AuditTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 审核业务总协调编排器 (AuditTaskCoordinator)。
 *
 * <p>职责与边界说明：
 * <ul>
 *   <li><b>所属边界</b>：审核微服务核心工作流调度组件，专注审核任务通用骨架（查重、建单、执行派发、持久化、回调驱动）；</li>
 *   <li><b>协作对象</b>：
 *     <ul>
 *       <li>{@link AuditTaskRepository}：负责审核聚合根的落库与状态流转；</li>
 *       <li>{@link AuditDetailRepository}：负责多维度审查证据快照的批量持久化；</li>
 *       <li>{@link AuditExecutorRouter}：负责根据业务类型分派给对应的业务专属审核执行器（如视频、评论、头像）；</li>
 *       <li>{@link AuditCallbackService}：终局判定自动联动下游微服务完成门禁对齐。</li>
 *     </ul>
 *   </li>
 *   <li><b>不应承担的工作</b>：不再硬编码具体的文字扫描、封面探测或抽帧算法细节，具体算法编排委托给 {@link AuditExecutor} 策略实现。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditTaskCoordinator {

    /** 审核任务聚合根仓储。 */
    private final AuditTaskRepository auditTaskRepository;

    /** 审核维度证据明细仓储。 */
    private final AuditDetailRepository auditDetailRepository;

    /** 审核执行器路由派发组件。 */
    private final AuditExecutorRouter executorRouter;

    /** 结果回调通知服务。 */
    private final AuditCallbackService callbackService;

    /**
     * 通用审核任务全生命周期编排与调度（支持多业务类型）。
     *
     * <p><b>幂等性保障</b>：若同一业务标的已存在处于进行中的审核任务，自动跳过重复提交，返回已存在的任务；<br>
     * <b>调度策略</b>：
     * 1. 查重并落库初始化任务；<br>
     * 2. 通过执行器路由器派发给专属审核器执行多维度机审；<br>
     * 3. 批量持久化证据明细并更新任务聚合根终局状态；<br>
     * 4. 若已达成终局状态 (FINISHED)，触发下游业务微服务回调。
     * </p>
     *
     * @param context 审核执行上下文（包含业务类型、标的 ID、文本快照及多媒体资产标识）
     * @return 经过机审裁决或挂起流转后的审核任务聚合根实体
     */
    @Transactional
    public AuditTask submitTask(AuditContext context) {
        log.info("开始受理通用提审任务: bizType=[{}], bizId=[{}], bizVid=[{}], authorId=[{}]",
                context.bizType(), context.bizId(), context.bizVid(), context.authorId());

        // 步骤 1：幂等防重检查：检查是否已有同一业务标的处于 RECEIVED 或 MACHINE_AUDITING
        Optional<AuditTask> latestOpt = auditTaskRepository.findLatestByBiz(context.bizType(), context.bizId());
        if (latestOpt.isPresent()) {
            AuditTask existing = latestOpt.get();
            if (existing.getStage() == AuditStage.RECEIVED || existing.getStage() == AuditStage.MACHINE_AUDITING) {
                log.warn("业务 [{}:{}] 已存在正在执行中的审核任务 [{}], 忽略重复提交",
                        context.bizType(), context.bizId(), existing.getTaskNo());
                return existing;
            }
        }

        // 步骤 2：工厂方法创建初始审核任务聚合根并完成持久化
        AuditTask task = AuditTask.createTask(
                context.bizType(),
                context.bizId(),
                context.bizVid(),
                context.authorId(),
                context.title(),
                context.description(),
                context.coverFileId(),
                context.videoFileId()
        );
        auditTaskRepository.insert(task);

        // 步骤 3：状态机流转：进入机审中 (MACHINE_AUDITING)
        task.startMachineAudit();
        auditTaskRepository.updateById(task);

        // 步骤 4：补充 taskId 后构建执行上下文，并通过路由器分派给专属业务执行器执行机审流水线
        AuditContext executionContext = AuditContext.builder()
                .taskId(task.getId())
                .bizType(context.bizType())
                .bizId(context.bizId())
                .bizVid(context.bizVid())
                .authorId(context.authorId())
                .title(context.title())
                .description(context.description())
                .coverFileId(context.coverFileId())
                .videoFileId(context.videoFileId())
                .build();
        AuditExecutor executor = executorRouter.route(task.getBizType());
        AuditExecutionResult executionResult = executor.execute(executionContext);

        // 步骤 5：将引擎执行明细转为领域证据实体并批量持久化
        List<AuditDetail> details = executionResult.details().stream()
                .map(r -> r.toAuditDetail(task.getId()))
                .toList();
        auditDetailRepository.insertBatch(details);

        // 步骤 6：更新任务聚合根终审阶段与结论
        task.completeMachineAudit(executionResult.overallLevel(), executionResult.summaryReason());
        auditTaskRepository.updateById(task);

        // 步骤 7：若机审直接产生终局结果（PASSED 放行或 REJECTED 驳回），立即联动下游微服务完成门禁流转
        if (task.getStage() == AuditStage.FINISHED) {
            callbackService.callbackContentService(task);
        }

        return task;
    }

    /**
     * 处理视频提审任务全生命周期编排（快捷包装入口）。
     *
     * <p><b>幂等性保障</b>：若同一视频已存在处于进行中的审核任务，自动跳过重复提交，返回已存在的任务；<br>
     * 内部基于统一的 {@link AuditContext#forVideo} 构建上下文并委托给 {@link #submitTask(AuditContext)}。
     * </p>
     *
     * @param videoId 关联视频全局主键 ID (UUID 32位)
     * @param vid 视频公开对外短码 (如 cv10086)
     * @param authorId 创作者账号唯一标识
     * @param title 提审时的标题文本快照
     * @param description 提审时的简介文本快照
     * @param coverFileId 封面图片资产 ID
     * @param videoFileId 主视频文件资产 ID
     * @return 经过机审裁决或挂起流转后的审核任务聚合根实体
     */
    @Transactional
    public AuditTask processVideoSubmission(
            String videoId,
            String vid,
            String authorId,
            String title,
            String description,
            String coverFileId,
            String videoFileId
    ) {
        AuditContext context = AuditContext.forVideo(
                null, videoId, vid, authorId, title, description, coverFileId, videoFileId
        );
        return submitTask(context);
    }
}
